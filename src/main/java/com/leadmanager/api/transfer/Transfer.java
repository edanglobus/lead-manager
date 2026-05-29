package com.leadmanager.api.transfer;

import java.math.BigDecimal;
import java.time.Instant;

import com.leadmanager.api.common.audit.BaseEntity;
import com.leadmanager.api.job.JobState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One edge in the transfer chain: User A (proposer) offers a job to
 * User B (candidate) at {@code commissionPct} cut.
 * <p>
 * <b>State transitions owned by the entity.</b> Every status change
 * goes through {@link #accept()}, {@link #decline()},
 * {@link #cancel()}, or {@link #expire()}. Each validates against
 * {@link TransferStatus#canTransitionTo(TransferStatus)} and stamps
 * {@code decidedAt}. The service tier NEVER assigns {@code status}
 * directly — it calls the entity methods and lets them enforce the
 * invariants.
 * <p>
 * <b>{@code preTransferState} captures where the job was BEFORE this
 * transfer was proposed.</b> On {@link #decline()} and
 * {@link #cancel()}, the service tier reads this field and restores
 * the job to that state. Without it, a declined transfer would have
 * to guess between {@link JobState#OPEN_GENERAL},
 * {@link JobState#OPEN_TODAY}, and {@link JobState#ASSIGNED}
 * (re-transfer case) — keeping the previous state on the row makes
 * the rewind deterministic.
 * <p>
 * <b>Why {@code proposedAt} is not a Java field.</b> The
 * {@code proposed_at} column exists at the DB (default {@code now()})
 * for forward-compat with a future "scheduled proposals" feature
 * where it would diverge from {@code created_at}. For v1 the two are
 * always equal — callers needing "when was this transfer proposed"
 * should use {@link BaseEntity#getCreatedAt()}.
 */
@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer extends BaseEntity {

    /** The job being transferred. Never changes. */
    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    /** The proposer — originator or current assignee at proposal time. */
    @Column(name = "from_user_id", nullable = false, updatable = false)
    private Long fromUserId;

    /** The candidate — who must accept or decline. */
    @Column(name = "to_user_id", nullable = false, updatable = false)
    private Long toUserId;

    /**
     * Commission cut as a percentage in {@code [0, 100]}.
     * {@code BigDecimal} per CLAUDE.md — never {@code double} for
     * money-adjacent values. The DB CHECK constraint enforces the
     * range; this is here as defence in depth.
     */
    @Column(name = "commission_pct", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal commissionPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private TransferStatus status;

    /**
     * The job's state when this transfer was proposed. Used by the
     * service tier to rewind on decline/cancel. Immutable for the
     * life of the row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pre_transfer_state", length = 32, nullable = false, updatable = false)
    private JobState preTransferState;

    /**
     * When the transfer was resolved (accepted / declined / cancelled
     * / expired). {@code null} while {@code PROPOSED}; set by the
     * transition method when the status changes.
     */
    @Column(name = "decided_at")
    private Instant decidedAt;

    /**
     * Optional TTL. The slice 6 {@code @Scheduled} expirer compares
     * this against {@code now()} and calls {@link #expire()} on rows
     * past their deadline. Currently {@code null} for all transfers
     * proposed in slice 5 — TTL behaviour lands later.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Builder
    private Transfer(Long jobId,
                     Long fromUserId,
                     Long toUserId,
                     BigDecimal commissionPct,
                     JobState preTransferState,
                     Instant expiresAt) {
        this.jobId = jobId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.commissionPct = commissionPct;
        this.preTransferState = preTransferState;
        this.status = TransferStatus.PROPOSED;
        this.expiresAt = expiresAt;
        // decidedAt stays null until a transition method runs.
    }

    // -------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------

    public void accept()  { transitionTo(TransferStatus.ACCEPTED);  }
    public void decline() { transitionTo(TransferStatus.DECLINED);  }
    public void cancel()  { transitionTo(TransferStatus.CANCELLED); }
    public void expire()  { transitionTo(TransferStatus.EXPIRED);   }

    private void transitionTo(TransferStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalTransferTransitionException(this.status, target);
        }
        this.status = target;
        this.decidedAt = Instant.now();
    }

    /**
     * Thrown when {@link #accept}, {@link #decline}, {@link #cancel},
     * or {@link #expire} is called on a transfer that's already
     * terminal. The service tier catches this and re-throws as
     * {@code ApiException(TRANSFER_STATE_TRANSITION_NOT_ALLOWED)}.
     */
    public static final class IllegalTransferTransitionException extends RuntimeException {
        private final TransferStatus from;
        private final TransferStatus to;

        public IllegalTransferTransitionException(TransferStatus from, TransferStatus to) {
            super("Illegal transfer transition: " + from + " -> " + to);
            this.from = from;
            this.to = to;
        }

        public TransferStatus from() { return from; }
        public TransferStatus to()   { return to;   }
    }
}
