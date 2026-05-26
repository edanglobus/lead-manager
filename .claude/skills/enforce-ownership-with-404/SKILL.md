---
name: enforce-ownership-with-404
description: Use when an endpoint operates on a per-user resource (delete my area, edit my profile, view my job, read my refresh token) and ownership must be enforced. Captures the project rule that "row exists but isn't yours" surfaces as 404 RESOURCE_NOT_FOUND, never 403. The deliberately-merged-with-not-found behaviour stops attackers from enumerating other users' ids by comparing response codes. Trigger whenever the user asks to add an endpoint that mutates or reads a resource owned by the authenticated user.
---

# Enforcing ownership without leaking existence

## The rule

When the authenticated user acts on a resource id that **either does not exist OR exists but belongs to a different user**, the response MUST be `404 RESOURCE_NOT_FOUND` with an RFC 7807 body. Never `403 FORBIDDEN`. Never different bodies for the two cases.

## Why 404, not 403

A 403 leaks information. The attacker tries `DELETE /api/v1/service-areas/{id}` for `id = 1, 2, 3, ...`:

- `403` on `id=42` → "this row exists, just not mine" → **enumerated another user's area id**.
- `404` on `id=43` → "no such row" → useful signal too.

If both unknown-and-not-yours return 404, the attacker can't distinguish them and gets no usable signal.

## The implementation

### Repository — owner-scoped query

Use a derived query that filters by both id and `userId`. The repository never exposes a "just by id" lookup for owned resources.

```java
Optional<ServiceArea> findByIdAndUserId(Long id, Long userId);
```

The `Optional.empty()` returned by this method represents **both** "id doesn't exist" and "id exists but is someone else's row" — the caller can't tell them apart, by design.

### Service — throw 404 on empty

```java
@Override
@Transactional
public void delete(Long userId, Long areaId) {
    ServiceArea area = repository.findByIdAndUserId(areaId, userId)
            .orElseThrow(() -> new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Service area not found"));
    repository.delete(area);
}
```

Notes:

- The `ApiException` detail is **deliberately generic**. Don't write "Service area 42 belongs to user 17" — that information is exactly what we're trying to hide.
- Take `userId` as an explicit parameter, not from `SecurityContextHolder`. Ownership is a service-tier contract; controllers and tests should be able to call this with an arbitrary user id.

### Controller — no auth logic

The controller does nothing security-relevant. It pulls the principal's id and delegates:

```java
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@AuthenticationPrincipal AuthenticatedUser me,
                   @PathVariable Long id) {
    service.delete(me.id(), id);
}
```

The controller does NOT call `findById` then check ownership. That's a TOCTOU race in waiting and forks the security logic across files.

## Tests that prove it

### Service-level unit test

```java
@Test
void delete_returns404_whenAreaDoesNotExistOrBelongsToAnotherUser() {
    // Repository returns Optional.empty() for both cases — the service
    // must treat them identically (always 404, never 403).
    when(areaRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(7L, 99L))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

    verify(areaRepository, never()).delete(any(ServiceArea.class));
}
```

The test name explicitly says "OR BELONGS TO ANOTHER USER" — without that, a future reader might naïvely split the cases and re-introduce the leak.

### Controller-level test

```java
@Test
void delete_returns404_whenServiceReportsNotFoundOrNotOwned() throws Exception {
    when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
    doThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "..."))
            .when(serviceAreaService).delete(PRINCIPAL_USER_ID, 999L);

    mockMvc.perform(delete(ENDPOINT + "/999")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
}
```

## Where this applies

Any endpoint that takes an id in the path and operates on a per-user resource:

- `DELETE /service-areas/{id}` ✅ (slice 3)
- `GET    /jobs/{id}` (future slice 4)
- `PUT    /jobs/{id}` (future slice 4)
- `POST   /transfers/{id}/accept` (future slice 5)
- `GET    /refresh-tokens/{id}` (hypothetical)

Conversely it does NOT apply to:

- `GET /service-categories` — reference data, same answer for every authenticated user.
- `POST /auth/login` — no resource id involved.
- `GET /users/me` — no path parameter; the path itself binds to the principal.

## Anti-patterns to refuse

- **`findById` + ownership check in the controller.** Forks security logic; trivial to forget in the next endpoint.
- **Returning 403 for "not yours".** Leaks existence. The CLAUDE.md "Standardized Errors" rule covers this implicitly — generic, leak-free messages — and this skill makes it explicit.
- **Logging the unmasked detail.** Server-side logs can say "user 7 attempted to delete area 42 owned by user 17"; the HTTP response cannot. Keep the leak-free message in the `ApiException` and write the rich version via `log.warn(...)` if forensics matters.

## Related

- `add-rfc7807-error` — for understanding how `ApiException → RESOURCE_NOT_FOUND` becomes the wire body.
- `add-webmvc-test-with-jwt-auth` — the "business 4xx" case in that test template is almost always this pattern.
- `lead-manager-vertical-slice` — the service-tier conventions where this lives.
