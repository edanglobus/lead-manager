---
name: add-rfc7807-error
description: Use when adding a new business error condition to the Lead Manager backend (e.g. a new domain rule violation). Covers extending the ErrorCode enum, throwing ApiException, the wire-contract guarantees of the existing GlobalExceptionHandler, and security-sensitive guidance around generic error messages. Trigger whenever the user asks to "return X error", "handle the case where Y", or whenever a service needs to surface a business error.
---

# Adding a new RFC 7807 error in the Lead Manager backend

## The architecture (already wired in slice 0)

| Component | Role | File |
|---|---|---|
| `ErrorCode` enum | Catalog of every error condition with stable type URI, HTTP status, title | `common/exception/ErrorCode.java` |
| `ApiException` | Base typed exception services throw | `common/exception/ApiException.java` |
| `GlobalExceptionHandler` | `@RestControllerAdvice` translating exceptions → `application/problem+json` | `common/exception/GlobalExceptionHandler.java` |
| `JwtAuthenticationEntryPoint` | Custom 401 for unauthenticated requests | `common/security/JwtAuthenticationEntryPoint.java` |

**You do not write controller-side `try/catch` or `@ExceptionHandler` methods.** The advice handles it. Just throw `ApiException(code, detail)` from the service.

## Steps to add a new error

### 1. Extend the enum

```java
// In ErrorCode.java, in the appropriate position (errors are ordered roughly by HTTP status)
JOB_NOT_OPEN(HttpStatus.CONFLICT, "job-not-open", "Job is not in an open state"),
```

- `slug` becomes part of the public type URI (`https://leadmanager.com/errors/job-not-open`).
- `title` is short, generic, no PII, no specifics — same for every occurrence.
- Once a code is added and merged, do not rename it. Clients pattern-match on the type URI.

### 2. Throw from the service

```java
if (!job.state().isOpen()) {
    throw new ApiException(
        ErrorCode.JOB_NOT_OPEN,
        "Job %d is in state %s and cannot accept transfers".formatted(job.getId(), job.state()));
}
```

The `detail` field IS allowed to be specific (job id, state name) — it's the human-readable message. Avoid PII (no email, no phone, no customer address) in the detail.

### 3. Test the wire contract

Always assert against `code`, `type`, and `status` — those are the stable contract. Never assert against `detail` or `title` (they're for humans).

```java
mockMvc.perform(post("/api/v1/jobs/42/transfers").contentType(JSON).content(body))
    .andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value(ErrorCode.JOB_NOT_OPEN.name()))
    .andExpect(jsonPath("$.type").value(ErrorCode.JOB_NOT_OPEN.typeUri()))
    .andExpect(jsonPath("$.status").value(409));
```

## Security-sensitive guidance

### Don't enable enumeration attacks

When the response distinguishes between two error conditions, an attacker can probe to learn which is true. Examples from this project:

- `INVALID_CREDENTIALS` is used for BOTH "no such email" AND "wrong password" — same code, same message, same status, same detail. An attacker can't tell if an email is registered.
- `/auth/logout` returns `204` whether the token was valid or bogus — same response, can't enumerate valid refresh tokens.

When you add a new error: think about who else could end up triggering it adversarially. If response shape leaks info, **collapse to a generic error**.

### Stack traces never leak

`GlobalExceptionHandler.handleUnexpected` (the catch-all) returns `INTERNAL_ERROR` with a generic message. The full stack is logged at `ERROR` server-side. `application.yml` also pins `server.error.include-stacktrace: never`.

If you find yourself wanting to expose details for debugging, don't. Add log lines instead.

## When to add a new code vs. reuse an existing one

| Reuse existing | Add new |
|---|---|
| Same root cause, same client remediation ("re-validate input", "re-authenticate") | Different remediation client must take |
| Same security posture (no enumeration leak) | Different status code is genuinely warranted |

Examples:
- A new "displayName too long" error → reuse `VALIDATION_FAILED` (Bean Validation already handles it).
- A new "transfer already accepted" error → add new code (`TRANSFER_ALREADY_DECIDED`), client needs a distinct UX message.

## Status code mapping cheat sheet

| Class | Use for |
|---|---|
| `400 BAD_REQUEST` | Malformed input, validation failures, type errors |
| `401 UNAUTHORIZED` | Authentication problems (missing/invalid credentials, expired tokens) |
| `403 FORBIDDEN` | Authenticated but not permitted (different from 401) |
| `404 NOT_FOUND` | Resource genuinely doesn't exist (or pretend it doesn't, for privacy) |
| `409 CONFLICT` | State conflict (duplicate email, optimistic-lock failure, illegal state transition) |
| `422 UNPROCESSABLE_ENTITY` | Syntactically valid request, semantically invalid (rarely needed; usually 400 or 409 fits) |
| `500 INTERNAL_ERROR` | Catch-all only; never thrown deliberately |

## Reference

- Canonical wire shape: `application/problem+json` per RFC 7807.
- Spring's `ProblemDetail` (built into Framework 6) is what `GlobalExceptionHandler` returns.
- All current codes: `src/main/java/com/leadmanager/api/common/exception/ErrorCode.java`.
