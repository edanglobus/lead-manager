package com.leadmanager.api.job;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.leadmanager.api.transfer.Transfer;

/**
 * Decides whether a given caller may see a given job, and at what
 * level of detail.
 * <p>
 * <b>Pure function</b> — no state, no side effects, no repository
 * calls. The caller (the service tier) loads the
 * {@code Optional<Transfer>} and passes it in. Keeping the rule pure
 * lets it be unit-tested without a Spring context and reused from
 * any path that needs to know "what does this user see right now."
 * <p>
 * <b>The rule (slice 5).</b>
 * <ul>
 *   <li>The {@code originator} sees the {@link Visibility#FULL} job.</li>
 *   <li>The {@code current assignee} sees the {@link Visibility#FULL} job.</li>
 *   <li>A {@code transfer candidate}, while a transfer to them is
 *       still {@code PROPOSED}, sees the {@link Visibility#MASKED}
 *       view — the wire DTO omits {@code customerAddress} and
 *       {@code customerPhone} so the candidate can decide on the
 *       offer without being able to bypass the platform.</li>
 *   <li>Everyone else: {@link Visibility#NONE} (the service tier
 *       maps this to a 404, never a 403, preserving the
 *       no-id-enumeration invariant).</li>
 * </ul>
 * <p>
 * Slice 5 deliberately does NOT widen visibility to the
 * "open feed" — anyone browsing leads in their trade. That's a
 * separate concern and lands when the feed query is built.
 */
@Component
public class JobVisibilityPolicy {

    public enum Visibility {
        /** Caller is originator or current assignee — full view. */
        FULL,

        /** Caller is the target of an open transfer proposal — masked view. */
        MASKED,

        /** Caller has no relationship to this job — 404. */
        NONE
    }

    /**
     * @param callerUserId   the authenticated caller's id
     * @param job            the job under consideration
     * @param openProposal   the {@code PROPOSED} transfer for this
     *                       job, if any. {@link Optional#empty()} when
     *                       there is none.
     */
    public Visibility visibilityFor(Long callerUserId,
                                    Job job,
                                    Optional<Transfer> openProposal) {

        if (Objects.equals(callerUserId, job.getOriginatorUserId())) {
            return Visibility.FULL;
        }
        if (Objects.equals(callerUserId, job.getCurrentAssigneeUserId())) {
            return Visibility.FULL;
        }
        if (openProposal.isPresent()
                && Objects.equals(callerUserId, openProposal.get().getToUserId())) {
            return Visibility.MASKED;
        }
        return Visibility.NONE;
    }
}
