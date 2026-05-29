package com.leadmanager.api.job;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.servicecategory.ServiceCategory;
import com.leadmanager.api.servicecategory.ServiceCategoryRepository;
import com.leadmanager.api.transfer.TransferRepository;
import com.leadmanager.api.transfer.TransferStatus;

@Service
public class JobServiceImpl implements JobService {

    /** Same SRID + factory pattern as ServiceAreaServiceImpl. */
    private static final int SRID_WGS84 = 4326;
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private final JobRepository jobRepository;
    private final JobStateTransitionRepository transitionRepository;
    private final ServiceCategoryRepository categoryRepository;
    private final TransferRepository transferRepository;
    private final JobVisibilityPolicy visibilityPolicy;

    public JobServiceImpl(JobRepository jobRepository,
                          JobStateTransitionRepository transitionRepository,
                          ServiceCategoryRepository categoryRepository,
                          TransferRepository transferRepository,
                          JobVisibilityPolicy visibilityPolicy) {
        this.jobRepository = jobRepository;
        this.transitionRepository = transitionRepository;
        this.categoryRepository = categoryRepository;
        this.transferRepository = transferRepository;
        this.visibilityPolicy = visibilityPolicy;
    }

    // -------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Job create(Long actorUserId, JobCreateCommand command) {
        // Category must exist AND be active. The FK alone only checks
        // existence; an active=false trade would slip past it.
        ServiceCategory category = categoryRepository.findById(command.serviceCategoryId())
                .filter(ServiceCategory::isActive)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Service category not found"));

        JobState initialState = command.today() ? JobState.OPEN_TODAY : JobState.OPEN_GENERAL;

        Job job = Job.builder()
                .originatorUserId(actorUserId)
                .serviceCategoryId(category.getId())
                .state(initialState)
                .title(command.title())
                .description(command.description())
                .customerName(command.customerName())
                .customerPhone(command.customerPhone())
                .customerAddress(command.customerAddress())
                .customerLocation(buildPoint(command.customerLatitude(), command.customerLongitude()))
                .priceCents(command.priceCents())
                .currency(command.currency())
                .scheduledFor(command.scheduledFor())
                .build();

        Job saved = jobRepository.save(job);
        transitionRepository.save(saved.initialTransition(actorUserId));
        return saved;
    }

    // -------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public JobAccess findOne(Long callerUserId, Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(JobServiceImpl::notFound);

        // Pull the open proposal (if any) so the policy can decide
        // MASKED vs NONE for a non-owner caller. Cheap lookup against
        // the partial unique index transfers_open_per_job_uq.
        JobVisibilityPolicy.Visibility visibility = visibilityPolicy.visibilityFor(
                callerUserId,
                job,
                transferRepository.findFirstByJobIdAndStatus(jobId, TransferStatus.PROPOSED));

        if (visibility == JobVisibilityPolicy.Visibility.NONE) {
            throw notFound();
        }
        return new JobAccess(job, visibility);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Job> listMyOriginated(Long userId) {
        return jobRepository.findAllByOriginatorUserIdOrderByIdDesc(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Job> listMyAssigned(Long userId) {
        return jobRepository.findAllByCurrentAssigneeUserIdOrderByIdDesc(userId);
    }

    // -------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Job selfAssign(Long callerUserId, Long jobId) {
        // Only the originator can self-assign. Transfer-to-someone-else
        // goes through PENDING_TRANSFER (slice 5) which has its own
        // authorisation rules.
        Job job = loadForUpdateAndRequire(jobId, isOriginator(callerUserId));
        return applyTransition(() -> job.assignTo(callerUserId, callerUserId, "self-assign"), job);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Job start(Long callerUserId, Long jobId) {
        Job job = loadForUpdateAndRequire(jobId, isCurrentAssignee(callerUserId));
        return applyTransition(() -> job.moveTo(JobState.IN_PROGRESS, callerUserId, null), job);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Job complete(Long callerUserId, Long jobId) {
        Job job = loadForUpdateAndRequire(jobId, isCurrentAssignee(callerUserId));
        return applyTransition(() -> job.moveTo(JobState.COMPLETED, callerUserId, null), job);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Job close(Long callerUserId, Long jobId) {
        // Originator confirms payment received. Ledger entries land in
        // slice 8 via a domain event off this transition.
        Job job = loadForUpdateAndRequire(jobId, isOriginator(callerUserId));
        return applyTransition(() -> job.moveTo(JobState.CLOSED_PAID, callerUserId, null), job);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Job cancel(Long callerUserId, Long jobId, String reason) {
        // Either side may cancel — both have legitimate reasons. The
        // entity's state machine rejects cancelling an already-terminal
        // job; that surfaces as 409 via applyTransition.
        Job job = loadForUpdateAndRequire(
                jobId,
                isOriginator(callerUserId).or(isCurrentAssignee(callerUserId)));
        return applyTransition(() -> job.moveTo(JobState.CANCELLED, callerUserId, reason), job);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Loads the job under a pessimistic write lock and verifies the
     * caller passes the given authorization predicate. Missing-id and
     * not-authorized BOTH return 404 (the merged-cases rule).
     */
    private Job loadForUpdateAndRequire(Long jobId, Predicate<Job> authorized) {
        return jobRepository.findByIdForUpdate(jobId)
                .filter(authorized)
                .orElseThrow(JobServiceImpl::notFound);
    }

    /**
     * Wraps the entity's state-machine call:
     *   1. catches the entity's IllegalStateTransitionException and
     *      re-throws as ApiException(JOB_STATE_TRANSITION_NOT_ALLOWED)
     *      so callers see a uniform RFC 7807 error;
     *   2. persists the produced JobStateTransition row;
     *   3. saves the mutated job, mapping
     *      OptimisticLockingFailureException to ApiException(CONCURRENT_MODIFICATION).
     */
    private Job applyTransition(java.util.function.Supplier<JobStateTransition> transitionFn, Job job) {
        final JobStateTransition row;
        try {
            row = transitionFn.get();
        } catch (Job.IllegalStateTransitionException ex) {
            throw new ApiException(
                    ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED,
                    "Job in state " + ex.from() + " cannot transition to " + ex.to());
        }

        transitionRepository.save(row);

        try {
            return jobRepository.save(job);
        } catch (OptimisticLockingFailureException ex) {
            throw new ApiException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "Job was modified concurrently; please retry");
        }
    }

    private static Predicate<Job> isOriginator(Long userId) {
        return job -> Objects.equals(job.getOriginatorUserId(), userId);
    }

    private static Predicate<Job> isCurrentAssignee(Long userId) {
        return job -> Objects.equals(job.getCurrentAssigneeUserId(), userId);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Job not found");
    }

    private static Point buildPoint(double latitude, double longitude) {
        // JTS uses (X, Y) = (longitude, latitude) — same convention as
        // ServiceAreaServiceImpl. Centralised inside the service so
        // callers can keep "lat first" naming locally.
        Point p = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(SRID_WGS84);
        return p;
    }
}
