package com.leadmanager.api.job;

/**
 * Result of {@link JobService#findOne(Long, Long)}: the {@link Job}
 * the caller is allowed to see, together with the
 * {@link JobVisibilityPolicy.Visibility visibility level} that
 * decides which DTO shape the HTTP layer renders.
 * <p>
 * The service never throws when the caller is allowed to see SOME
 * version of the job; the visibility on the returned access object
 * is the dispatch hint. The {@code 404 RESOURCE_NOT_FOUND} path is
 * reserved for {@link JobVisibilityPolicy.Visibility#NONE} — same
 * merged-cases rule as elsewhere (no enumeration leak).
 */
public record JobAccess(Job job, JobVisibilityPolicy.Visibility visibility) {
}
