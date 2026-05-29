package com.leadmanager.api.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for the {@link JobState} state machine.
 * <p>
 * The state machine is the heart of the marketplace; these tests are
 * intentionally exhaustive about which transitions are legal so a
 * future refactor of the {@code ALLOWED} matrix cannot quietly broaden
 * the rules. New legal moves require a test change; new illegal moves
 * stay locked down by the catch-all parameterized test.
 */
class JobStateTest {

    // ---------- "can transition to" matrix ----------

    @Test
    void openGeneral_allowedTransitions() {
        assertThat(JobState.OPEN_GENERAL.allowedNextStates()).containsExactlyInAnyOrder(
                JobState.PENDING_TRANSFER, JobState.ASSIGNED,
                JobState.CANCELLED, JobState.EXPIRED);
    }

    @Test
    void openToday_allowedTransitions() {
        assertThat(JobState.OPEN_TODAY.allowedNextStates()).containsExactlyInAnyOrder(
                JobState.PENDING_TRANSFER, JobState.ASSIGNED,
                JobState.CANCELLED, JobState.EXPIRED);
    }

    @Test
    void pendingTransfer_allowedTransitions() {
        assertThat(JobState.PENDING_TRANSFER.allowedNextStates()).containsExactlyInAnyOrder(
                JobState.ASSIGNED,
                JobState.OPEN_GENERAL, JobState.OPEN_TODAY,
                JobState.CANCELLED, JobState.EXPIRED);
    }

    @Test
    void assigned_allowedTransitions() {
        assertThat(JobState.ASSIGNED.allowedNextStates()).containsExactlyInAnyOrder(
                JobState.IN_PROGRESS, JobState.PENDING_TRANSFER, JobState.CANCELLED);
    }

    @Test
    void inProgress_allowedTransitions() {
        assertThat(JobState.IN_PROGRESS.allowedNextStates()).containsExactlyInAnyOrder(
                JobState.COMPLETED, JobState.CANCELLED);
    }

    @Test
    void completed_allowedTransitions() {
        assertThat(JobState.COMPLETED.allowedNextStates()).containsExactlyInAnyOrder(
                JobState.CLOSED_PAID, JobState.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = JobState.class, names = {"CLOSED_PAID", "CANCELLED", "EXPIRED"})
    void terminalStates_haveNoOutgoingTransitions(JobState terminal) {
        assertThat(terminal.allowedNextStates()).isEmpty();
        assertThat(terminal.isTerminal()).isTrue();
        // Sanity check the negation: any non-terminal state must have at least one outgoing edge.
        for (JobState other : JobState.values()) {
            if (other != terminal && !other.isTerminal()) {
                assertThat(other.allowedNextStates()).isNotEmpty();
            }
        }
    }

    // ---------- canTransitionTo: positive + negative ----------

    @Test
    void canTransitionTo_returnsTrueForAllowedMoves() {
        assertThat(JobState.OPEN_GENERAL.canTransitionTo(JobState.ASSIGNED)).isTrue();
        assertThat(JobState.ASSIGNED.canTransitionTo(JobState.IN_PROGRESS)).isTrue();
        assertThat(JobState.IN_PROGRESS.canTransitionTo(JobState.COMPLETED)).isTrue();
        assertThat(JobState.COMPLETED.canTransitionTo(JobState.CLOSED_PAID)).isTrue();
    }

    @Test
    void canTransitionTo_returnsFalseForIllegalMoves() {
        // Cannot skip states.
        assertThat(JobState.OPEN_GENERAL.canTransitionTo(JobState.IN_PROGRESS)).isFalse();
        // Cannot resurrect a closed-paid job.
        assertThat(JobState.CLOSED_PAID.canTransitionTo(JobState.OPEN_GENERAL)).isFalse();
        // Cannot self-loop.
        assertThat(JobState.OPEN_GENERAL.canTransitionTo(JobState.OPEN_GENERAL)).isFalse();
        // Once IN_PROGRESS, no more transfers.
        assertThat(JobState.IN_PROGRESS.canTransitionTo(JobState.PENDING_TRANSFER)).isFalse();
    }

    // ---------- isOpen ----------

    @Test
    void isOpen_identifiesFeedStates() {
        assertThat(JobState.OPEN_GENERAL.isOpen()).isTrue();
        assertThat(JobState.OPEN_TODAY.isOpen()).isTrue();
        // ASSIGNED is non-terminal but NOT on the public feed.
        assertThat(JobState.ASSIGNED.isOpen()).isFalse();
        assertThat(JobState.PENDING_TRANSFER.isOpen()).isFalse();
        assertThat(JobState.CLOSED_PAID.isOpen()).isFalse();
    }

    // ---------- defensiveness ----------

    @Test
    void allowedNextStates_isDefensivelyCopied() {
        Set<JobState> copy = JobState.OPEN_GENERAL.allowedNextStates();
        copy.clear();
        assertThat(JobState.OPEN_GENERAL.allowedNextStates())
                .as("mutating the returned set must not affect the matrix")
                .isNotEmpty();
    }
}
