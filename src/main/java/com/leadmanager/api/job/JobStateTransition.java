package com.leadmanager.api.job;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only audit row recording one state change of a {@link Job}.
 * <p>
 * Deliberately does NOT extend {@code BaseEntity}: that class brings
 * {@code @Version} and an {@code updated_at} column, neither of which
 * are meaningful for an immutable audit row. The only timestamp here
 * is {@code occurred_at} (DB default {@code now()}, populated by JPA
 * Auditing on insert).
 * <p>
 * <b>Immutability.</b> Once persisted, transition rows are NEVER
 * updated or deleted by application code (only cascaded away when
 * the owning job is hard-deleted, which is itself rare). The entity
 * has no setters; the {@link lombok.Builder} is the only construction
 * path.
 * <p>
 * <b>{@code fromState} nullability.</b> {@code null} marks the row
 * inserted when the job was first created — there's no "previous"
 * state to record. Every subsequent row has both endpoints populated.
 */
@Entity
@Table(name = "job_state_transitions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class JobStateTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    @Column(name = "from_state", length = 32, updatable = false)
    @Enumerated(EnumType.STRING)
    private JobState fromState;

    @Column(name = "to_state", length = 32, nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    private JobState toState;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    /**
     * When the transition happened. Populated by JPA Auditing on
     * insert (per {@link com.leadmanager.api.common.audit.JpaAuditingConfig});
     * the DB {@code DEFAULT now()} is a safety net for direct SQL.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    @CreatedDate
    private Instant occurredAt;

    @Builder
    private JobStateTransition(Long jobId,
                               JobState fromState,
                               JobState toState,
                               Long actorUserId,
                               String reason) {
        this.jobId = jobId;
        this.fromState = fromState;
        this.toState = toState;
        this.actorUserId = actorUserId;
        this.reason = reason;
    }
}
