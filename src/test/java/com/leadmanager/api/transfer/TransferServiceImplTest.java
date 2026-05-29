package com.leadmanager.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.job.Job;
import com.leadmanager.api.job.JobRepository;
import com.leadmanager.api.job.JobState;
import com.leadmanager.api.job.JobStateTransition;
import com.leadmanager.api.job.JobStateTransitionRepository;
import com.leadmanager.api.job.JobVisibilityPolicy;
import com.leadmanager.api.user.User;
import com.leadmanager.api.user.UserRepository;

/**
 * Service-tier unit test for {@link TransferServiceImpl}.
 * <p>
 * Covers each operation's authz rule (404 on failure) plus the
 * state-transition + state-restoration semantics. Mocks the
 * repositories; uses the REAL {@link JobVisibilityPolicy} so the
 * history-visibility test exercises the actual rule.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private static final Long ORIGINATOR_ID = 1L;
    private static final Long CANDIDATE_ID  = 42L;
    private static final Long STRANGER_ID   = 99L;
    private static final Long JOB_ID        = 7L;
    private static final Long TRANSFER_ID   = 88L;
    private static final BigDecimal CUT     = new BigDecimal("20.00");

    @Mock private TransferRepository transferRepository;
    @Mock private JobRepository jobRepository;
    @Mock private JobStateTransitionRepository jobTransitionRepository;
    @Mock private UserRepository userRepository;

    private final JobVisibilityPolicy visibilityPolicy = new JobVisibilityPolicy();

    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(
                transferRepository,
                jobRepository,
                jobTransitionRepository,
                userRepository,
                visibilityPolicy);
    }

    // ===================================================================
    // propose
    // ===================================================================

    @Test
    void propose_movesJobToPendingTransfer_createsProposedRow_andAuditTrail() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(userRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(stubUser(CANDIDATE_ID)));
        when(transferRepository.findFirstByJobIdAndStatus(JOB_ID, TransferStatus.PROPOSED))
                .thenReturn(Optional.empty());
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.save(job)).thenReturn(job);

        Transfer result = service.propose(ORIGINATOR_ID,
                new TransferProposeCommand(JOB_ID, CANDIDATE_ID, CUT));

        assertThat(job.getState()).isEqualTo(JobState.PENDING_TRANSFER);
        assertThat(job.getCurrentAssigneeUserId())
                .as("leaving OPEN to PENDING_TRANSFER must not set an assignee")
                .isNull();
        assertThat(result.getFromUserId()).isEqualTo(ORIGINATOR_ID);
        assertThat(result.getToUserId()).isEqualTo(CANDIDATE_ID);
        assertThat(result.getCommissionPct()).isEqualByComparingTo(CUT);
        assertThat(result.getStatus()).isEqualTo(TransferStatus.PROPOSED);
        assertThat(result.getPreTransferState()).isEqualTo(JobState.OPEN_GENERAL);

        verify(jobTransitionRepository).save(any(JobStateTransition.class));
    }

    @Test
    void propose_returns400_whenToUserEqualsActor() {
        assertThatThrownBy(() -> service.propose(ORIGINATOR_ID,
                new TransferProposeCommand(JOB_ID, ORIGINATOR_ID, CUT)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void propose_returns404_whenToUserDoesNotExist() {
        when(userRepository.findById(CANDIDATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.propose(ORIGINATOR_ID,
                new TransferProposeCommand(JOB_ID, CANDIDATE_ID, CUT)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void propose_returns404_whenCallerNotOriginatorOrAssignee() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(userRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(stubUser(CANDIDATE_ID)));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.propose(STRANGER_ID,
                new TransferProposeCommand(JOB_ID, CANDIDATE_ID, CUT)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void propose_returns409_whenJobNotOpen() {
        Job job = newJob(JobState.ASSIGNED, ORIGINATOR_ID);
        when(userRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(stubUser(CANDIDATE_ID)));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.propose(ORIGINATOR_ID,
                new TransferProposeCommand(JOB_ID, CANDIDATE_ID, CUT)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED);
    }

    @Test
    void propose_returns409_whenOpenProposalAlreadyExists_serviceTierPreCheck() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(userRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(stubUser(CANDIDATE_ID)));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        Transfer existing = Transfer.builder()
                .jobId(JOB_ID).fromUserId(ORIGINATOR_ID).toUserId(77L)
                .commissionPct(CUT).preTransferState(JobState.OPEN_GENERAL).build();
        when(transferRepository.findFirstByJobIdAndStatus(JOB_ID, TransferStatus.PROPOSED))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.propose(ORIGINATOR_ID,
                new TransferProposeCommand(JOB_ID, CANDIDATE_ID, CUT)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OPEN_TRANSFER_ALREADY_EXISTS);
    }

    @Test
    void propose_returns409_whenDbPartialUniqueRejectsRace() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(userRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(stubUser(CANDIDATE_ID)));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(transferRepository.findFirstByJobIdAndStatus(JOB_ID, TransferStatus.PROPOSED))
                .thenReturn(Optional.empty());
        when(jobRepository.save(job)).thenReturn(job);
        when(transferRepository.save(any(Transfer.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> service.propose(ORIGINATOR_ID,
                new TransferProposeCommand(JOB_ID, CANDIDATE_ID, CUT)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OPEN_TRANSFER_ALREADY_EXISTS);
    }

    // ===================================================================
    // accept
    // ===================================================================

    @Test
    void accept_candidateMovesTransferToAccepted_andJobToAssignedWithNewAssignee() {
        Job job = newJob(JobState.PENDING_TRANSFER, null);
        Transfer transfer = newProposed(JobState.OPEN_GENERAL);

        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        when(jobRepository.save(job)).thenReturn(job);

        Transfer result = service.accept(CANDIDATE_ID, TRANSFER_ID);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(job.getState()).isEqualTo(JobState.ASSIGNED);
        assertThat(job.getCurrentAssigneeUserId()).isEqualTo(CANDIDATE_ID);
        verify(jobTransitionRepository).save(any(JobStateTransition.class));
    }

    @Test
    void accept_returns404_whenCallerNotCandidate() {
        Transfer transfer = newProposed(JobState.OPEN_GENERAL);
        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.accept(STRANGER_ID, TRANSFER_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(transferRepository, never()).save(any());
    }

    @Test
    void accept_returns409_whenTransferAlreadyTerminal() {
        Transfer transfer = newProposed(JobState.OPEN_GENERAL);
        transfer.cancel();  // make it terminal
        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));

        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.accept(CANDIDATE_ID, TRANSFER_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TRANSFER_STATE_TRANSITION_NOT_ALLOWED);
    }

    @Test
    void accept_returns404_whenTransferMissing() {
        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(CANDIDATE_ID, TRANSFER_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ===================================================================
    // decline
    // ===================================================================

    @Test
    void decline_restoresJobToPreTransferState() {
        Job job = newJob(JobState.PENDING_TRANSFER, null);
        Transfer transfer = newProposed(JobState.OPEN_TODAY);  // came from OPEN_TODAY

        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        when(jobRepository.save(job)).thenReturn(job);

        Transfer result = service.decline(CANDIDATE_ID, TRANSFER_ID);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.DECLINED);
        assertThat(job.getState())
                .as("job restored to its OPEN_TODAY pre-transfer state")
                .isEqualTo(JobState.OPEN_TODAY);
    }

    @Test
    void decline_returns404_whenCallerNotCandidate() {
        Transfer transfer = newProposed(JobState.OPEN_GENERAL);
        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.decline(STRANGER_ID, TRANSFER_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ===================================================================
    // cancel
    // ===================================================================

    @Test
    void cancel_proposerRescindsAndJobRestored() {
        Job job = newJob(JobState.PENDING_TRANSFER, null);
        Transfer transfer = newProposed(JobState.OPEN_GENERAL);

        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        when(jobRepository.save(job)).thenReturn(job);

        Transfer result = service.cancel(ORIGINATOR_ID, TRANSFER_ID);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(job.getState()).isEqualTo(JobState.OPEN_GENERAL);
    }

    @Test
    void cancel_returns404_whenCallerNotProposer() {
        // Candidate cannot cancel — only the proposer (from_user) can.
        Transfer transfer = newProposed(JobState.OPEN_GENERAL);
        when(transferRepository.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.cancel(CANDIDATE_ID, TRANSFER_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ===================================================================
    // historyForJob
    // ===================================================================

    @Test
    void historyForJob_returnsRows_whenCallerIsOriginator() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(transferRepository.findFirstByJobIdAndStatus(JOB_ID, TransferStatus.PROPOSED))
                .thenReturn(Optional.empty());
        Transfer t = newProposed(JobState.OPEN_GENERAL);
        when(transferRepository.findAllByJobIdOrderByIdDesc(JOB_ID))
                .thenReturn(java.util.List.of(t));

        assertThat(service.historyForJob(ORIGINATOR_ID, JOB_ID)).containsExactly(t);
    }

    @Test
    void historyForJob_returns404_whenCallerHasNoVisibility() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(transferRepository.findFirstByJobIdAndStatus(JOB_ID, TransferStatus.PROPOSED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.historyForJob(STRANGER_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---------- helpers ----------

    private static Job newJob(JobState state, Long assignee) {
        Point loc = FACTORY.createPoint(new Coordinate(34.78, 32.08));
        loc.setSRID(4326);
        Job job = Job.builder()
                .originatorUserId(ORIGINATOR_ID).serviceCategoryId(7L).state(state)
                .title("x").description("x")
                .customerName("x").customerPhone("x").customerAddress("x")
                .customerLocation(loc).priceCents(100L).currency("USD")
                .build();
        setField(job, "id", JOB_ID);
        if (assignee != null) {
            setField(job, "currentAssigneeUserId", assignee);
        }
        return job;
    }

    private static Transfer newProposed(JobState preState) {
        Transfer t = Transfer.builder()
                .jobId(JOB_ID)
                .fromUserId(ORIGINATOR_ID)
                .toUserId(CANDIDATE_ID)
                .commissionPct(CUT)
                .preTransferState(preState)
                .build();
        setField(t, "id", TRANSFER_ID);
        return t;
    }

    private static User stubUser(Long id) {
        // Whatever construction path UserRepository's findById would
        // return. We only care about presence, not field values.
        User u = User.builder()
                .email("u" + id + "@test.io")
                .passwordHash("$2a$12$" + "x".repeat(53))
                .displayName("U" + id)
                .build();
        setField(u, "id", id);
        return u;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not set " + name, e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
