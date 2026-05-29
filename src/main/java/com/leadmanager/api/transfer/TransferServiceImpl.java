package com.leadmanager.api.transfer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.job.Job;
import com.leadmanager.api.job.JobRepository;
import com.leadmanager.api.job.JobState;
import com.leadmanager.api.job.JobStateTransition;
import com.leadmanager.api.job.JobStateTransitionRepository;
import com.leadmanager.api.job.JobVisibilityPolicy;
import com.leadmanager.api.user.UserRepository;

@Service
public class TransferServiceImpl implements TransferService {

    private final TransferRepository transferRepository;
    private final JobRepository jobRepository;
    private final JobStateTransitionRepository jobTransitionRepository;
    private final UserRepository userRepository;
    private final JobVisibilityPolicy visibilityPolicy;

    public TransferServiceImpl(TransferRepository transferRepository,
                               JobRepository jobRepository,
                               JobStateTransitionRepository jobTransitionRepository,
                               UserRepository userRepository,
                               JobVisibilityPolicy visibilityPolicy) {
        this.transferRepository = transferRepository;
        this.jobRepository = jobRepository;
        this.jobTransitionRepository = jobTransitionRepository;
        this.userRepository = userRepository;
        this.visibilityPolicy = visibilityPolicy;
    }

    // -------------------------------------------------------------------
    // propose
    // -------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Transfer propose(Long actorUserId, TransferProposeCommand command) {
        if (Objects.equals(actorUserId, command.toUserId())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Cannot propose a transfer to yourself");
        }

        // Verify candidate exists. The FK on save would catch a missing
        // user but the error message would be opaque; a pre-check is
        // worth the extra query for a clean 404.
        if (userRepository.findById(command.toUserId()).isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Target user not found");
        }

        Job job = jobRepository.findByIdForUpdate(command.jobId())
                .filter(isOriginatorOrAssignee(actorUserId))
                .orElseThrow(TransferServiceImpl::jobNotFound);

        // Slice 5 only supports proposing from the two OPEN_* states.
        // The state machine allows ASSIGNED -> PENDING_TRANSFER too
        // (re-transfer), but restoring the previous assignee on
        // decline/cancel requires extra plumbing; deferred to a later
        // slice. A non-open job here surfaces as the standard 409.
        if (!job.getState().isOpen()) {
            throw new ApiException(
                    ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED,
                    "Transfers can only be proposed for OPEN_GENERAL or OPEN_TODAY jobs");
        }

        // UX-friendly pre-check. The partial unique index closes the
        // race; this just gives a friendlier error if the caller asks
        // in the obvious order.
        if (transferRepository.findFirstByJobIdAndStatus(job.getId(), TransferStatus.PROPOSED).isPresent()) {
            throw new ApiException(
                    ErrorCode.OPEN_TRANSFER_ALREADY_EXISTS,
                    "An open transfer proposal already exists for this job");
        }

        JobState preState = job.getState();
        JobStateTransition transitionRow = job.moveTo(
                JobState.PENDING_TRANSFER, actorUserId, "transfer proposed");

        Transfer transfer = Transfer.builder()
                .jobId(job.getId())
                .fromUserId(actorUserId)
                .toUserId(command.toUserId())
                .commissionPct(command.commissionPct())
                .preTransferState(preState)
                .build();

        // Persist the audit row + the job + the transfer. If the
        // partial unique index rejects a racing insert, we map the
        // DataIntegrityViolationException to OPEN_TRANSFER_ALREADY_EXISTS.
        jobTransitionRepository.save(transitionRow);
        saveJobMappingConcurrency(job);
        try {
            return transferRepository.save(transfer);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(
                    ErrorCode.OPEN_TRANSFER_ALREADY_EXISTS,
                    "An open transfer proposal already exists for this job");
        }
    }

    // -------------------------------------------------------------------
    // accept / decline / cancel — all use the same loading + locking
    // skeleton; only the authz predicate and the transitions differ.
    // -------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Transfer accept(Long actorUserId, Long transferId) {
        return resolveTransfer(
                actorUserId, transferId,
                isToUser(actorUserId),
                (transfer, job) -> {
                    applyTransferTransition(transfer::accept, transfer);
                    JobStateTransition row = job.assignTo(actorUserId, actorUserId, "transfer accepted");
                    jobTransitionRepository.save(row);
                    saveJobMappingConcurrency(job);
                });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Transfer decline(Long actorUserId, Long transferId) {
        return resolveTransfer(
                actorUserId, transferId,
                isToUser(actorUserId),
                (transfer, job) -> {
                    applyTransferTransition(transfer::decline, transfer);
                    JobStateTransition row = job.moveTo(
                            transfer.getPreTransferState(), actorUserId, "transfer declined");
                    jobTransitionRepository.save(row);
                    saveJobMappingConcurrency(job);
                });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Transfer cancel(Long actorUserId, Long transferId) {
        return resolveTransfer(
                actorUserId, transferId,
                isFromUser(actorUserId),
                (transfer, job) -> {
                    applyTransferTransition(transfer::cancel, transfer);
                    JobStateTransition row = job.moveTo(
                            transfer.getPreTransferState(), actorUserId, "transfer cancelled");
                    jobTransitionRepository.save(row);
                    saveJobMappingConcurrency(job);
                });
    }

    // -------------------------------------------------------------------
    // history
    // -------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<Transfer> historyForJob(Long callerUserId, Long jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(TransferServiceImpl::jobNotFound);

        Optional<Transfer> openProposal =
                transferRepository.findFirstByJobIdAndStatus(jobId, TransferStatus.PROPOSED);

        if (visibilityPolicy.visibilityFor(callerUserId, job, openProposal)
                == JobVisibilityPolicy.Visibility.NONE) {
            throw jobNotFound();
        }

        return transferRepository.findAllByJobIdOrderByIdDesc(jobId);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Load + lock + authorize + mutate skeleton for accept/decline/
     * cancel. Always locks Transfer first then Job (consistent order
     * across operations to avoid deadlocks). The {@code mutator}
     * gets both rows, already locked, and writes both transitions.
     */
    private Transfer resolveTransfer(Long actorUserId,
                                     Long transferId,
                                     Predicate<Transfer> authorized,
                                     TransferMutator mutator) {

        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .filter(authorized)
                .orElseThrow(TransferServiceImpl::transferNotFound);

        Job job = jobRepository.findByIdForUpdate(transfer.getJobId())
                .orElseThrow(TransferServiceImpl::jobNotFound);

        mutator.mutate(transfer, job);

        try {
            return transferRepository.save(transfer);
        } catch (OptimisticLockingFailureException ex) {
            throw new ApiException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Transfer was modified concurrently; please retry");
        }
    }

    /**
     * Wraps a {@code transfer.accept()/decline()/cancel()} call:
     * catches the entity's {@link Transfer.IllegalTransferTransitionException}
     * and re-throws as a uniform RFC 7807 409.
     */
    private static void applyTransferTransition(Runnable transitionFn, Transfer transfer) {
        try {
            transitionFn.run();
        } catch (Transfer.IllegalTransferTransitionException ex) {
            throw new ApiException(
                    ErrorCode.TRANSFER_STATE_TRANSITION_NOT_ALLOWED,
                    "Transfer in state " + ex.from() + " cannot transition to " + ex.to());
        }
    }

    private Job saveJobMappingConcurrency(Job job) {
        try {
            return jobRepository.save(job);
        } catch (OptimisticLockingFailureException ex) {
            throw new ApiException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Job was modified concurrently; please retry");
        }
    }

    private static Predicate<Job> isOriginatorOrAssignee(Long userId) {
        return job -> Objects.equals(job.getOriginatorUserId(), userId)
                || Objects.equals(job.getCurrentAssigneeUserId(), userId);
    }

    private static Predicate<Transfer> isToUser(Long userId) {
        return t -> Objects.equals(t.getToUserId(), userId);
    }

    private static Predicate<Transfer> isFromUser(Long userId) {
        return t -> Objects.equals(t.getFromUserId(), userId);
    }

    private static ApiException jobNotFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Job not found");
    }

    private static ApiException transferNotFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Transfer not found");
    }

    /** Functional interface for the mutate step of resolveTransfer. */
    @FunctionalInterface
    private interface TransferMutator {
        void mutate(Transfer transfer, Job job);
    }
}
