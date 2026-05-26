package com.leadmanager.api.job;

import java.time.Instant;

import org.locationtech.jts.geom.Point;

import com.leadmanager.api.common.audit.BaseEntity;

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
 * The marketplace lead. Lives through the {@link JobState} 9-state
 * lifecycle from creation to {@code CLOSED_PAID} (success) or
 * {@code CANCELLED}/{@code EXPIRED} (terminal failure).
 * <p>
 * <b>Customer block is embedded.</b> Customers do not log in and have
 * no per-row lifecycle of their own, so the four customer fields live
 * on this row directly rather than in a sibling table. The
 * blind-transfer masking rule (CLAUDE.md §1) is enforced at the
 * DTO mapper layer — both providers in a chain read the same row;
 * the wire view differs by caller relationship + current state.
 * <p>
 * <b>State changes are owned by the entity.</b> Every transition goes
 * through {@link #assignTo(Long, Long, String)} or
 * {@link #moveTo(JobState, Long, String)}. Both validate against
 * {@link JobState#canTransitionTo(JobState)} and produce a
 * {@link JobStateTransition} record for the service tier to persist
 * — the service NEVER sets {@code state} directly. Optimistic locking
 * via {@code @Version} on {@link BaseEntity} surfaces a concurrent
 * edit as {@code OptimisticLockingFailureException}, mapped by the
 * service tier to a 409 CONFLICT.
 * <p>
 * <b>{@code currentAssigneeUserId} is denormalized.</b> The canonical
 * chain lives in {@code transfers} (slice 5); the canonical history
 * lives in {@code job_state_transitions}. This field exists so
 * "jobs assigned to me right now" stays a single-index lookup
 * (see the partial index {@code jobs_assignee_active_ix} in V8).
 * It is set when the job enters {@code ASSIGNED} and cleared when
 * it leaves (except {@code ASSIGNED → IN_PROGRESS}, where the
 * same assignee continues).
 */
@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job extends BaseEntity {

    /** The provider who created the lead. Never changes once set. */
    @Column(name = "originator_user_id", nullable = false, updatable = false)
    private Long originatorUserId;

    /**
     * Provider currently holding the lead. {@code null} when the job
     * is open or terminal-without-assignment. Mutated only by
     * {@link #assignTo(Long, Long, String)} and
     * {@link #moveTo(JobState, Long, String)} — never set directly.
     */
    @Column(name = "current_assignee_user_id")
    private Long currentAssigneeUserId;

    @Column(name = "service_category_id", nullable = false)
    private Long serviceCategoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 32, nullable = false)
    private JobState state;

    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    // ----- Embedded customer block ---------------------------------------

    @Column(name = "customer_name", length = 120, nullable = false)
    private String customerName;

    @Column(name = "customer_phone", length = 32, nullable = false)
    private String customerPhone;

    @Column(name = "customer_address", length = 255, nullable = false)
    private String customerAddress;

    @Column(name = "customer_location", columnDefinition = "geography(Point,4326)", nullable = false)
    private Point customerLocation;

    // ----- Money ---------------------------------------------------------

    /**
     * Price in minor units (cents for USD, agorot for ILS, …). Integer
     * by contract — see CLAUDE.md §"Currency Handling". The DTO layer
     * surfaces a {@link java.math.BigDecimal} to the wire if a
     * decimal representation is needed.
     */
    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    /**
     * ISO 4217 alpha-3 currency code. VARCHAR(3) at the DB — exact
     * length is enforced by the {@code jobs_currency_format} CHECK
     * regex, not by CHAR's space-padding (which would otherwise
     * round-trip {@code "USD "} on reads from a CHAR column).
     */
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /**
     * Optional intended start time. Used by {@code OPEN_TODAY},
     * {@code ASSIGNED}, and scheduled work flows. Null for
     * {@code OPEN_GENERAL} (no specific time).
     */
    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Builder
    private Job(Long originatorUserId,
                Long serviceCategoryId,
                JobState state,
                String title,
                String description,
                String customerName,
                String customerPhone,
                String customerAddress,
                Point customerLocation,
                Long priceCents,
                String currency,
                Instant scheduledFor) {
        this.originatorUserId = originatorUserId;
        this.serviceCategoryId = serviceCategoryId;
        this.state = state;
        this.title = title;
        this.description = description;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.customerAddress = customerAddress;
        this.customerLocation = customerLocation;
        this.priceCents = priceCents;
        this.currency = currency;
        this.scheduledFor = scheduledFor;
    }

    // -------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------

    /**
     * Transitions the job to {@code ASSIGNED} with the given
     * {@code assigneeUserId} populated as the denormalized assignee.
     * Use this rather than {@link #moveTo(JobState, Long, String)} for
     * any transition INTO {@code ASSIGNED}, because the assignee id
     * is mandatory in that state.
     *
     * @throws IllegalStateTransitionException if the current state
     *         cannot move directly to {@code ASSIGNED}
     */
    public JobStateTransition assignTo(Long assigneeUserId,
                                       Long actorUserId,
                                       String reason) {
        if (assigneeUserId == null) {
            throw new IllegalArgumentException("assigneeUserId is required");
        }
        JobState previous = requireTransitionAllowed(JobState.ASSIGNED);
        this.currentAssigneeUserId = assigneeUserId;
        this.state = JobState.ASSIGNED;
        return buildTransition(previous, JobState.ASSIGNED, actorUserId, reason);
    }

    /**
     * Transitions the job to any state except {@code ASSIGNED}. For
     * {@code ASSIGNED}, call {@link #assignTo(Long, Long, String)}
     * instead so the assignee id is always provided.
     * <p>
     * Leaving {@code ASSIGNED} for anything other than
     * {@code IN_PROGRESS} clears the denormalized assignee — the job
     * is no longer "held" by anyone.
     *
     * @throws IllegalArgumentException        if {@code target} is
     *         {@code ASSIGNED}
     * @throws IllegalStateTransitionException if the current state
     *         cannot move directly to {@code target}
     */
    public JobStateTransition moveTo(JobState target,
                                     Long actorUserId,
                                     String reason) {
        if (target == JobState.ASSIGNED) {
            throw new IllegalArgumentException(
                    "Use assignTo() for transitions into ASSIGNED");
        }
        JobState previous = requireTransitionAllowed(target);

        // Leaving ASSIGNED clears the denormalized assignee unless
        // the next state is IN_PROGRESS (same provider continues).
        if (previous == JobState.ASSIGNED && target != JobState.IN_PROGRESS) {
            this.currentAssigneeUserId = null;
        }

        this.state = target;
        return buildTransition(previous, target, actorUserId, reason);
    }

    /**
     * Builds the initial transition row (the one with {@code fromState=null})
     * captured at the moment of {@code Job} creation. The service tier
     * persists this alongside the {@code Job} insert so the audit
     * trail begins on row 1.
     */
    public JobStateTransition initialTransition(Long actorUserId) {
        return buildTransition(null, this.state, actorUserId, null);
    }

    private JobState requireTransitionAllowed(JobState target) {
        if (!this.state.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this.state, target);
        }
        return this.state;
    }

    private JobStateTransition buildTransition(JobState from,
                                               JobState to,
                                               Long actorUserId,
                                               String reason) {
        return JobStateTransition.builder()
                .jobId(this.getId())
                .fromState(from)
                .toState(to)
                .actorUserId(actorUserId)
                .reason(reason)
                .build();
    }

    /**
     * Thrown by {@link #assignTo} / {@link #moveTo} when the requested
     * move is not in the state machine's allowed-transitions matrix.
     * The service tier catches this and re-throws as an
     * {@code ApiException} with the right RFC 7807 code so the wire
     * contract stays uniform.
     */
    public static final class IllegalStateTransitionException extends RuntimeException {
        private final JobState from;
        private final JobState to;

        public IllegalStateTransitionException(JobState from, JobState to) {
            super("Illegal state transition: " + from + " -> " + to);
            this.from = from;
            this.to = to;
        }

        public JobState from() { return from; }
        public JobState to()   { return to;   }
    }
}
