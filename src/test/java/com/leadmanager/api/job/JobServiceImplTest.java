package com.leadmanager.api.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.servicecategory.ServiceCategory;
import com.leadmanager.api.servicecategory.ServiceCategoryRepository;

/**
 * Service-tier unit test for {@link JobServiceImpl}.
 * <p>
 * The state machine itself is exercised in {@link JobStateTest} and
 * {@link JobTest}; this class focuses on what the SERVICE adds on top
 * of the entity:
 * <ul>
 *   <li>per-method authorization (originator vs assignee) merged to 404
 *       on failure;</li>
 *   <li>mapping the entity's {@code IllegalStateTransitionException}
 *       to {@code ApiException(JOB_STATE_TRANSITION_NOT_ALLOWED)};</li>
 *   <li>mapping {@code OptimisticLockingFailureException} on save to
 *       {@code ApiException(CONCURRENT_MODIFICATION)};</li>
 *   <li>category existence + active check during create;</li>
 *   <li>the {@code today} flag deriving the right initial state.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobServiceImplTest {

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private static final Long ORIGINATOR_ID = 1L;
    private static final Long ASSIGNEE_ID   = 42L;
    private static final Long STRANGER_ID   = 99L;
    private static final Long JOB_ID        = 7L;
    private static final Long CATEGORY_ID   = 12L;

    @Mock private JobRepository jobRepository;
    @Mock private JobStateTransitionRepository transitionRepository;
    @Mock private ServiceCategoryRepository categoryRepository;
    @InjectMocks private JobServiceImpl service;

    private ServiceCategory activeCategory;

    @BeforeEach
    void setUp() {
        activeCategory = ServiceCategory.builder()
                .code("plumbing").displayName("Plumbing").active(true).sortOrder(120)
                .build();
        setField(activeCategory, "id", CATEGORY_ID);
    }

    // ===================================================================
    // create
    // ===================================================================

    @Test
    void create_persistsJobInOpenGeneral_andInitialTransition() {
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(activeCategory));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        Job result = service.create(ORIGINATOR_ID, commandToday(false));

        assertThat(result.getOriginatorUserId()).isEqualTo(ORIGINATOR_ID);
        assertThat(result.getServiceCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(result.getState()).isEqualTo(JobState.OPEN_GENERAL);
        assertThat(result.getCustomerLocation().getY()).isEqualTo(32.08);  // lat
        assertThat(result.getCustomerLocation().getX()).isEqualTo(34.78);  // lng
        assertThat(result.getCustomerLocation().getSRID()).isEqualTo(4326);

        // initial transition row was persisted alongside the job
        verify(transitionRepository).save(any(JobStateTransition.class));
    }

    @Test
    void create_initialStateIsOpenToday_whenTodayFlagSet() {
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(activeCategory));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        Job result = service.create(ORIGINATOR_ID, commandToday(true));

        assertThat(result.getState()).isEqualTo(JobState.OPEN_TODAY);
    }

    @Test
    void create_returns404_whenCategoryDoesNotExist() {
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(ORIGINATOR_ID, commandToday(false)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(jobRepository, never()).save(any());
        verify(transitionRepository, never()).save(any());
    }

    @Test
    void create_returns404_whenCategoryIsInactive() {
        ServiceCategory inactive = ServiceCategory.builder()
                .code("locksmith").displayName("Locksmith").active(false).sortOrder(80)
                .build();
        setField(inactive, "id", CATEGORY_ID);
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.create(ORIGINATOR_ID, commandToday(false)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ===================================================================
    // findOne — visibility
    // ===================================================================

    @Test
    void findOne_returnsJob_whenCallerIsOriginator() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        Job result = service.findOne(ORIGINATOR_ID, JOB_ID);

        assertThat(result).isSameAs(job);
    }

    @Test
    void findOne_returnsJob_whenCallerIsCurrentAssignee() {
        Job job = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        Job result = service.findOne(ASSIGNEE_ID, JOB_ID);

        assertThat(result).isSameAs(job);
    }

    @Test
    void findOne_returns404_whenCallerIsStranger() {
        Job job = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.findOne(STRANGER_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void findOne_returns404_whenJobMissing() {
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOne(ORIGINATOR_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ===================================================================
    // listMy*
    // ===================================================================

    @Test
    void listMyOriginated_delegatesWithOwnerScoping() {
        Job a = newJob(JobState.OPEN_GENERAL, null);
        Job b = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findAllByOriginatorUserIdOrderByIdDesc(ORIGINATOR_ID))
                .thenReturn(List.of(b, a));

        assertThat(service.listMyOriginated(ORIGINATOR_ID)).containsExactly(b, a);
    }

    @Test
    void listMyAssigned_delegatesWithAssigneeScoping() {
        Job j = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findAllByCurrentAssigneeUserIdOrderByIdDesc(ASSIGNEE_ID))
                .thenReturn(List.of(j));

        assertThat(service.listMyAssigned(ASSIGNEE_ID)).containsExactly(j);
    }

    // ===================================================================
    // selfAssign — originator only
    // ===================================================================

    @Test
    void selfAssign_originatorMovesOpenToAssigned_recordsTransition() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Job result = service.selfAssign(ORIGINATOR_ID, JOB_ID);

        assertThat(result.getState()).isEqualTo(JobState.ASSIGNED);
        assertThat(result.getCurrentAssigneeUserId()).isEqualTo(ORIGINATOR_ID);
        verify(transitionRepository).save(any(JobStateTransition.class));
    }

    @Test
    void selfAssign_returns404_whenCallerNotOriginator() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.selfAssign(STRANGER_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(jobRepository, never()).save(any());
        verify(transitionRepository, never()).save(any());
    }

    @Test
    void selfAssign_returns409_whenStateNotOpen() {
        // Job is already ASSIGNED — entity must refuse re-assignment.
        Job job = newJob(JobState.ASSIGNED, ORIGINATOR_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.selfAssign(ORIGINATOR_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED);

        verify(jobRepository, never()).save(any());
    }

    // ===================================================================
    // start — assignee only
    // ===================================================================

    @Test
    void start_assigneeMovesAssignedToInProgress() {
        Job job = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Job result = service.start(ASSIGNEE_ID, JOB_ID);

        assertThat(result.getState()).isEqualTo(JobState.IN_PROGRESS);
        assertThat(result.getCurrentAssigneeUserId())
                .as("IN_PROGRESS keeps the same assignee")
                .isEqualTo(ASSIGNEE_ID);
    }

    @Test
    void start_returns404_whenCallerNotAssignee() {
        Job job = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.start(ORIGINATOR_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void start_returns409_whenStateNotAssigned() {
        Job job = newJob(JobState.OPEN_GENERAL, null);
        // No assignee yet, but to test the illegal-transition path we
        // pretend the caller IS the assignee by aligning ids; otherwise
        // we'd hit the 404 path first.
        setField(job, "currentAssigneeUserId", ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.start(ASSIGNEE_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED);
    }

    // ===================================================================
    // complete — assignee only
    // ===================================================================

    @Test
    void complete_assigneeMovesInProgressToCompleted() {
        Job job = newJob(JobState.IN_PROGRESS, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Job result = service.complete(ASSIGNEE_ID, JOB_ID);

        assertThat(result.getState()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void complete_returns404_whenCallerNotAssignee() {
        Job job = newJob(JobState.IN_PROGRESS, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.complete(ORIGINATOR_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ===================================================================
    // close — originator only
    // ===================================================================

    @Test
    void close_originatorMovesCompletedToClosedPaid() {
        Job job = newJob(JobState.COMPLETED, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Job result = service.close(ORIGINATOR_ID, JOB_ID);

        assertThat(result.getState()).isEqualTo(JobState.CLOSED_PAID);
    }

    @Test
    void close_returns404_whenCallerNotOriginator() {
        Job job = newJob(JobState.COMPLETED, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        // The assignee is NOT allowed to close — only the originator can
        // confirm payment received.
        assertThatThrownBy(() -> service.close(ASSIGNEE_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ===================================================================
    // cancel — originator OR assignee
    // ===================================================================

    @Test
    void cancel_originatorCanCancelAssignedJob() {
        Job job = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Job result = service.cancel(ORIGINATOR_ID, JOB_ID, "customer flake");

        assertThat(result.getState()).isEqualTo(JobState.CANCELLED);
        assertThat(result.getCurrentAssigneeUserId())
                .as("CANCELLED clears the denorm assignee")
                .isNull();
    }

    @Test
    void cancel_assigneeCanCancelInProgressJob() {
        Job job = newJob(JobState.IN_PROGRESS, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Job result = service.cancel(ASSIGNEE_ID, JOB_ID, "unable to continue");

        assertThat(result.getState()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void cancel_returns404_whenCallerNeither() {
        Job job = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancel(STRANGER_ID, JOB_ID, "evil"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void cancel_returns409_whenJobAlreadyTerminal() {
        Job job = newJob(JobState.CLOSED_PAID, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancel(ORIGINATOR_ID, JOB_ID, "refund"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED);
    }

    // ===================================================================
    // concurrent modification
    // ===================================================================

    @Test
    void transition_maps_OptimisticLockingFailure_to_CONCURRENT_MODIFICATION() {
        Job job = newJob(JobState.ASSIGNED, ASSIGNEE_ID);
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.save(job))
                .thenThrow(new OptimisticLockingFailureException("boom"));

        assertThatThrownBy(() -> service.start(ASSIGNEE_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION);
    }

    // ===================================================================
    // not-found vs not-authorized merge
    // ===================================================================

    @Test
    void allTransitions_return404_whenJobMissing() {
        when(jobRepository.findByIdForUpdate(JOB_ID)).thenReturn(Optional.empty());

        // Spot-check one per category — all loadForUpdate sites go
        // through the same helper so a single missing-id assertion
        // covers the merged 404 invariant.
        assertThatThrownBy(() -> service.selfAssign(ORIGINATOR_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> service.start(ASSIGNEE_ID, JOB_ID))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> service.cancel(ORIGINATOR_ID, JOB_ID, "x"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---------- helpers ----------

    private static JobCreateCommand commandToday(boolean today) {
        return new JobCreateCommand(
                CATEGORY_ID,
                today,
                "Leaking faucet",
                "Kitchen sink, drips every 2 seconds",
                "Jane Doe",
                "+972-50-1234567",
                "Dizengoff 100, Tel Aviv",
                32.08,            // latitude
                34.78,            // longitude
                15_000L,          // priceCents
                "USD",
                today ? Instant.parse("2026-05-27T12:00:00Z") : null);
    }

    private static Job newJob(JobState state, Long assignee) {
        Point loc = FACTORY.createPoint(new Coordinate(34.78, 32.08));
        loc.setSRID(4326);
        Job job = Job.builder()
                .originatorUserId(ORIGINATOR_ID)
                .serviceCategoryId(CATEGORY_ID)
                .state(state)
                .title("Leaking faucet")
                .description("Kitchen sink, drips every 2 seconds")
                .customerName("Jane Doe")
                .customerPhone("+972-50-1234567")
                .customerAddress("Dizengoff 100, Tel Aviv")
                .customerLocation(loc)
                .priceCents(15_000L)
                .currency("USD")
                .build();
        setField(job, "id", JOB_ID);
        if (assignee != null) {
            setField(job, "currentAssigneeUserId", assignee);
        }
        return job;
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
