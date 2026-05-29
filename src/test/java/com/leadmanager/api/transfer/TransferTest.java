package com.leadmanager.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.leadmanager.api.job.JobState;
import com.leadmanager.api.transfer.Transfer.IllegalTransferTransitionException;

/**
 * Unit tests for {@link Transfer}'s state-machine methods.
 * <p>
 * Three invariants pinned:
 * <ul>
 *   <li>builder produces a row in {@code PROPOSED} with {@code decidedAt}
 *       null;</li>
 *   <li>{@code accept/decline/cancel/expire} on a {@code PROPOSED}
 *       row sets the new status AND stamps {@code decidedAt};</li>
 *   <li>any transition on a terminal row throws.</li>
 * </ul>
 */
class TransferTest {

    private static final Long JOB_ID    = 7L;
    private static final Long FROM_USER = 1L;
    private static final Long TO_USER   = 42L;
    private static final BigDecimal CUT = new BigDecimal("20.00");

    @Test
    void builder_producesProposedWithNullDecidedAt() {
        Transfer t = newProposed(JobState.OPEN_GENERAL);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.PROPOSED);
        assertThat(t.getDecidedAt()).isNull();
        assertThat(t.getPreTransferState()).isEqualTo(JobState.OPEN_GENERAL);
        assertThat(t.getJobId()).isEqualTo(JOB_ID);
        assertThat(t.getFromUserId()).isEqualTo(FROM_USER);
        assertThat(t.getToUserId()).isEqualTo(TO_USER);
        assertThat(t.getCommissionPct()).isEqualByComparingTo("20.00");
    }

    @Test
    void accept_movesProposedToAccepted_andStampsDecidedAt() {
        Transfer t = newProposed(JobState.OPEN_GENERAL);
        Instant before = Instant.now();

        t.accept();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(t.getDecidedAt()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void decline_movesProposedToDeclined_andStampsDecidedAt() {
        Transfer t = newProposed(JobState.OPEN_TODAY);

        t.decline();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.DECLINED);
        assertThat(t.getDecidedAt()).isNotNull();
    }

    @Test
    void cancel_movesProposedToCancelled_andStampsDecidedAt() {
        Transfer t = newProposed(JobState.ASSIGNED);

        t.cancel();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(t.getDecidedAt()).isNotNull();
    }

    @Test
    void expire_movesProposedToExpired_andStampsDecidedAt() {
        Transfer t = newProposed(JobState.OPEN_GENERAL);

        t.expire();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.EXPIRED);
        assertThat(t.getDecidedAt()).isNotNull();
    }

    @Test
    void accept_onAlreadyAccepted_throwsIllegalTransition() {
        Transfer t = newProposed(JobState.OPEN_GENERAL);
        t.accept();

        assertThatThrownBy(t::accept)
                .isInstanceOf(IllegalTransferTransitionException.class);
    }

    @Test
    void decline_onAlreadyCancelled_throwsIllegalTransition() {
        Transfer t = newProposed(JobState.OPEN_GENERAL);
        t.cancel();

        assertThatThrownBy(t::decline)
                .isInstanceOf(IllegalTransferTransitionException.class);
    }

    @Test
    void illegalTransitionException_carriesFromAndTo() {
        Transfer t = newProposed(JobState.OPEN_GENERAL);
        t.decline();

        try {
            t.accept();
        } catch (IllegalTransferTransitionException ex) {
            assertThat(ex.from()).isEqualTo(TransferStatus.DECLINED);
            assertThat(ex.to()).isEqualTo(TransferStatus.ACCEPTED);
            return;
        }
        throw new AssertionError("expected IllegalTransferTransitionException");
    }

    @Test
    void terminalState_doesNotMutateOnFailedTransition() {
        Transfer t = newProposed(JobState.OPEN_GENERAL);
        t.accept();
        Instant decidedAtBefore = t.getDecidedAt();

        try {
            t.decline();
        } catch (IllegalTransferTransitionException ignored) {
            // expected
        }

        assertThat(t.getStatus())
                .as("failed transition must not change status")
                .isEqualTo(TransferStatus.ACCEPTED);
        assertThat(t.getDecidedAt())
                .as("failed transition must not re-stamp decidedAt")
                .isEqualTo(decidedAtBefore);
    }

    @Test
    void builder_storesOptionalExpiresAt() {
        Instant ttl = Instant.parse("2026-12-31T23:59:59Z");

        Transfer t = Transfer.builder()
                .jobId(JOB_ID).fromUserId(FROM_USER).toUserId(TO_USER)
                .commissionPct(CUT)
                .preTransferState(JobState.OPEN_GENERAL)
                .expiresAt(ttl)
                .build();

        assertThat(t.getExpiresAt()).isEqualTo(ttl);
    }

    // ---------- helpers ----------

    private static Transfer newProposed(JobState preState) {
        return Transfer.builder()
                .jobId(JOB_ID)
                .fromUserId(FROM_USER)
                .toUserId(TO_USER)
                .commissionPct(CUT)
                .preTransferState(preState)
                .build();
    }
}
