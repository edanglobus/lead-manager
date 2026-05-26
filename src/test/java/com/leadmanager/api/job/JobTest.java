package com.leadmanager.api.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import com.leadmanager.api.job.Job.IllegalStateTransitionException;

/**
 * Unit tests for {@link Job}'s state-machine methods.
 * <p>
 * Covers the happy path (legal transitions produce the expected
 * {@link JobStateTransition} record + mutate the job), the denormalized
 * assignee field's lifecycle, and the failure modes the entity throws
 * before the service layer ever sees them.
 */
class JobTest {

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private static final Long ORIGINATOR = 1L;
    private static final Long PROVIDER_A = 42L;
    private static final Long PROVIDER_B = 99L;

    @Test
    void assignTo_validTransition_setsStateAndAssignee_andProducesTransitionRow() {
        Job job = newJob(JobState.OPEN_GENERAL);

        JobStateTransition row = job.assignTo(PROVIDER_A, ORIGINATOR, "self-assign");

        assertThat(job.getState()).isEqualTo(JobState.ASSIGNED);
        assertThat(job.getCurrentAssigneeUserId()).isEqualTo(PROVIDER_A);

        assertThat(row.getFromState()).isEqualTo(JobState.OPEN_GENERAL);
        assertThat(row.getToState()).isEqualTo(JobState.ASSIGNED);
        assertThat(row.getActorUserId()).isEqualTo(ORIGINATOR);
        assertThat(row.getReason()).isEqualTo("self-assign");
    }

    @Test
    void assignTo_rejectsNullAssignee() {
        Job job = newJob(JobState.OPEN_GENERAL);

        assertThatThrownBy(() -> job.assignTo(null, ORIGINATOR, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigneeUserId");
        assertThat(job.getState()).isEqualTo(JobState.OPEN_GENERAL);
    }

    @Test
    void assignTo_throwsWhenTransitionIllegal() {
        Job job = newJob(JobState.CLOSED_PAID);

        assertThatThrownBy(() -> job.assignTo(PROVIDER_A, ORIGINATOR, null))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(job.getState()).isEqualTo(JobState.CLOSED_PAID);
        assertThat(job.getCurrentAssigneeUserId()).isNull();
    }

    @Test
    void moveTo_assignedIsRejected_useAssignToInstead() {
        Job job = newJob(JobState.OPEN_GENERAL);

        assertThatThrownBy(() -> job.moveTo(JobState.ASSIGNED, ORIGINATOR, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignTo");
    }

    @Test
    void moveTo_assignedToInProgress_keepsAssignee() {
        Job job = newJob(JobState.OPEN_GENERAL);
        job.assignTo(PROVIDER_A, ORIGINATOR, null);

        JobStateTransition row = job.moveTo(JobState.IN_PROGRESS, PROVIDER_A, "starting");

        assertThat(job.getState()).isEqualTo(JobState.IN_PROGRESS);
        assertThat(job.getCurrentAssigneeUserId())
                .as("IN_PROGRESS is the same provider continuing — assignee remains")
                .isEqualTo(PROVIDER_A);
        assertThat(row.getFromState()).isEqualTo(JobState.ASSIGNED);
        assertThat(row.getToState()).isEqualTo(JobState.IN_PROGRESS);
    }

    @Test
    void moveTo_leavingAssignedForAnythingElse_clearsAssignee() {
        Job job = newJob(JobState.OPEN_GENERAL);
        job.assignTo(PROVIDER_A, ORIGINATOR, null);

        job.moveTo(JobState.CANCELLED, ORIGINATOR, "customer cancelled");

        assertThat(job.getState()).isEqualTo(JobState.CANCELLED);
        assertThat(job.getCurrentAssigneeUserId())
                .as("leaving ASSIGNED for any non-IN_PROGRESS state must clear the denorm field")
                .isNull();
    }

    @Test
    void moveTo_assignedToPendingTransfer_clearsAssignee() {
        Job job = newJob(JobState.OPEN_GENERAL);
        job.assignTo(PROVIDER_A, ORIGINATOR, null);

        job.moveTo(JobState.PENDING_TRANSFER, PROVIDER_A, "handing off");

        assertThat(job.getState()).isEqualTo(JobState.PENDING_TRANSFER);
        assertThat(job.getCurrentAssigneeUserId())
                .as("re-transfer clears the denorm field; the transfer record holds the candidate")
                .isNull();
    }

    @Test
    void moveTo_completedToClosedPaid_terminalSuccess() {
        Job job = newJob(JobState.IN_PROGRESS);
        // Simulate the in-progress provider context.
        forceAssignee(job, PROVIDER_A);

        job.moveTo(JobState.COMPLETED, PROVIDER_A, "done");
        // From COMPLETED, the provider is no longer "holding" the lead
        // — closing it is the originator's action.
        assertThat(job.getCurrentAssigneeUserId()).isEqualTo(PROVIDER_A);

        job.moveTo(JobState.CLOSED_PAID, ORIGINATOR, "paid");

        assertThat(job.getState()).isEqualTo(JobState.CLOSED_PAID);
        assertThat(JobState.CLOSED_PAID.isTerminal()).isTrue();
    }

    @Test
    void moveTo_throwsWhenTransitionIllegal_andLeavesStateUnchanged() {
        Job job = newJob(JobState.OPEN_GENERAL);

        assertThatThrownBy(() -> job.moveTo(JobState.CLOSED_PAID, ORIGINATOR, null))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(job.getState()).isEqualTo(JobState.OPEN_GENERAL);
    }

    @Test
    void initialTransition_recordsCreationRowWithNullFromState() {
        Job job = newJob(JobState.OPEN_TODAY);

        JobStateTransition row = job.initialTransition(ORIGINATOR);

        assertThat(row.getFromState()).isNull();
        assertThat(row.getToState()).isEqualTo(JobState.OPEN_TODAY);
        assertThat(row.getActorUserId()).isEqualTo(ORIGINATOR);
    }

    @Test
    void transferDeclineFlow_pendingBackToOpenGeneral() {
        Job job = newJob(JobState.OPEN_GENERAL);
        job.moveTo(JobState.PENDING_TRANSFER, ORIGINATOR, "proposing to B");

        // B declines — the service picks "back to OPEN_GENERAL" because
        // that's where we came from.
        JobStateTransition decline = job.moveTo(JobState.OPEN_GENERAL, PROVIDER_B, "declined");

        assertThat(job.getState()).isEqualTo(JobState.OPEN_GENERAL);
        assertThat(decline.getFromState()).isEqualTo(JobState.PENDING_TRANSFER);
    }

    @Test
    void illegalStateTransitionException_carriesFromAndTo() {
        Job job = newJob(JobState.CANCELLED);

        try {
            job.moveTo(JobState.IN_PROGRESS, ORIGINATOR, null);
        } catch (IllegalStateTransitionException ex) {
            assertThat(ex.from()).isEqualTo(JobState.CANCELLED);
            assertThat(ex.to()).isEqualTo(JobState.IN_PROGRESS);
            return;
        }
        throw new AssertionError("expected IllegalStateTransitionException");
    }

    // ---------- helpers ----------

    private static Job newJob(JobState state) {
        Point loc = FACTORY.createPoint(new Coordinate(34.78, 32.08));
        loc.setSRID(4326);
        return Job.builder()
                .originatorUserId(ORIGINATOR)
                .serviceCategoryId(7L)
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
    }

    /**
     * Some tests need to start from a state that's only reachable
     * mid-flow (e.g. IN_PROGRESS with an assignee set). The straight
     * path through {@code assignTo} + {@code moveTo} would litter the
     * arrange step; this reflection-based shortcut keeps tests focused
     * on the assertion being made.
     */
    private static void forceAssignee(Job job, Long assignee) {
        try {
            java.lang.reflect.Field f = Job.class.getDeclaredField("currentAssigneeUserId");
            f.setAccessible(true);
            f.set(job, assignee);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
