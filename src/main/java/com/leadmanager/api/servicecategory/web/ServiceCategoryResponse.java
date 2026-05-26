package com.leadmanager.api.servicecategory.web;

/**
 * Public JSON view of a {@link com.leadmanager.api.servicecategory.ServiceCategory}.
 * <p>
 * The set of fields here is the API contract. Adding a field is a
 * non-breaking change; removing or renaming one is breaking and MUST
 * coincide with bumping {@code ApiVersion}.
 * <p>
 * Deliberately omitted:
 * <ul>
 *   <li>{@code active} — the endpoint already filters to active rows;
 *       exposing the flag would invite clients to render inactive ones.</li>
 *   <li>{@code sortOrder} — internal display ordering. The list is already
 *       sorted server-side; the client should preserve order, not re-sort
 *       by a field that may change semantics later.</li>
 *   <li>{@code version}, {@code createdAt}, {@code updatedAt} — JPA
 *       implementation details with no client value.</li>
 * </ul>
 *
 * @param id          server-assigned category id (use this when posting
 *                    back as a foreign-key reference)
 * @param code        stable machine identifier (lowercase snake_case)
 * @param displayName human-readable label for UI rendering
 */
public record ServiceCategoryResponse(
        Long id,
        String code,
        String displayName
) {
}
