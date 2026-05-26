# Lead Manager Backend — Feature Specification

> The complete picture of what the backend will do when development is finished.
> Source of truth: the architecture plan at `~/.claude/plans/claude-act-as-the-quizzical-fiddle.md`.
> Status as of 2026-05-26: slices 1 & 2 merged to `dev`; slice 3 in progress.

---

## 1. The product in one paragraph

A peer-to-peer marketplace for independent service providers (HVAC techs, plumbers, electricians, …). A provider posts a job lead they cannot or do not want to execute, optionally transfers it to another provider for a commission, and the chain of transfers is recorded all the way to the provider who actually does the work and gets paid. The backend powers both a **web client (React/Expo Web)** and **native mobile apps (React Native iOS + Android)** — same API, same security posture, no browser-only assumptions.

---

## 2. Roll-out status (12 vertical slices)

| # | Branch | Theme | Status |
|---|---|---|---|
| 1 | `feat/user-auth` | Identity, JWT, refresh rotation, logout | ✅ Merged to `dev` |
| 2 | `chore/postgis-and-categories` | PostGIS extension + 12 seeded trades | ✅ Merged to `dev` |
| 3 | `feat/service-areas` | Per-provider geofenced coverage | 🟡 In progress (3.1–3.3 done; 3.4 nearby query + 3.5 PR remain) |
| 4 | `feat/job-core` | Job entity + 9-state lifecycle + transitions | ⬜ Next |
| 5 | `feat/transfers-and-blind-mask` | Transfer chains + blind data masking | ⬜ |
| 6 | `feat/outbox-and-notifications` | Transactional outbox + in-app inbox | ⬜ |
| 7 | `feat/device-tokens-and-push` | FCM + APNS push integration | ⬜ |
| 8 | `feat/ledger-and-commissions` | Double-entry ledger + payout math | ⬜ |
| 9 | `feat/ratings` | Bidirectional reveal-after-both ratings | ⬜ |
| 10 | `chore/idempotency` | `Idempotency-Key` header on all mutations | ⬜ |
| 11 | `chore/observability` | Structured logs, traces, metrics | ⬜ |
| 12 | `chore/rate-limiting` | Bucket4j, keyed on JWT subject | ⬜ |

---

## 3. Feature surface (when v1 is done)

### 3.1 Identity & access

- **Sign-up** with email + password (bcrypt-12, case-insensitive email uniqueness via functional index).
- **Login** → JWT access token (15 min) + opaque refresh token (30 days, persisted as SHA-256 hash).
- **Refresh rotation** — every refresh use rotates both tokens; old token is invalidated; the family is linked via `replaced_by_id` so a reused old token can revoke the entire chain.
- **Logout** — revokes the entire refresh family for the current user; idempotent (returns 204 even for bogus tokens, no enumeration).
- **`GET /users/me`** — fetch the current authenticated user profile.
- **Header-based auth only.** No cookies. Mobile clients don't have cookie jars, and bearer headers eliminate CSRF surface.

### 3.2 Provider coverage (Service Areas)

- **Curated trade taxonomy** seeded via Flyway (`appliance_repair`, `cleaning`, `computer_repair`, `electrical`, `gardening`, `handyman`, `hvac`, `locksmith`, `moving`, `painting`, `pest_control`, `plumbing`). Reference data — never edited by application code.
- **`GET /service-categories`** — list active trades, sorted, returned to both clients for picker UIs.
- **Service areas** — each provider declares one or more `(category, center point, radius)` geofences. Backed by PostGIS `geography(Point, 4326)` with a GIST index for sub-millisecond proximity queries.
- **`POST /service-areas`** — create coverage.
- **`GET /service-areas/me`** — list mine.
- **`DELETE /service-areas/{id}`** — remove one. 404-not-403 on someone else's id (prevents enumerating other users).
- **`GET /providers/nearby?lat=…&lng=…&categoryId=…`** — native PostGIS `ST_DWithin` query; returns providers whose service area covers the given point.

### 3.3 Job lifecycle (the marketplace core)

The Job is the lead. It has nine states arranged in a strict state machine:

```
OPEN_GENERAL ─┐
              ├─► PENDING_TRANSFER ─► ASSIGNED ─► IN_PROGRESS ─► COMPLETED ─► CLOSED_PAID
OPEN_TODAY  ─┘                                                     │
                                                                   ├─► CANCELLED
                                                                   └─► EXPIRED
```

- **`POST /jobs`** — create a lead. Embeds the customer block (name, phone, address, location) — no `Customer` table by design.
- **`GET /jobs/{id}`** — fetch a job. Returned representation depends on the caller's relationship to the job and the current state (see §3.4 masking).
- **`GET /jobs/feed?...`** — discoverable open leads scoped by category + geography.
- **`GET /jobs/mine?role=originator|assignee&state=…`** — caller's own jobs.
- **State transitions** are explicit endpoints (`POST /jobs/{id}/start`, `/complete`, `/close`, `/cancel`). Every transition is appended to an immutable `job_state_transitions` audit table.
- **Optimistic locking** on Job via `@Version`. Concurrent edits → `409 CONFLICT`.

### 3.4 Transfers (blind-mask is the killer feature)

- **`POST /jobs/{id}/transfers`** — User A proposes transferring the job to User B at a commission percentage. Partial unique index `(job_id) WHERE status='PROPOSED'` prevents two simultaneous proposals on the same job.
- **`POST /transfers/{id}/accept`** — User B accepts the terms; job state moves to `ASSIGNED`; the customer block becomes visible to B.
- **`POST /transfers/{id}/decline`** — User B declines; job returns to its previous open state.
- **Blind masking, enforced at the DTO layer (NEVER in the frontend):** while the transfer is `PROPOSED`, User B sees the job's category, geography (rough), and money — but **not** `customer_address` or `customer_phone`. The mapper strips those fields based on `(caller, job state, transfer state)`. The values are masked on the wire — the frontend never receives them at all.
- **Transfer chains** — a job can be transferred multiple times. The chain is the ordered list of accepted transfers, used by the ledger to compute payouts up the chain.
- **Pessimistic locking** (`SELECT … FOR UPDATE`) during state transitions to keep the chain consistent under concurrent operations.

### 3.5 Notifications & push

- **Transactional outbox** — domain events (`JobCreated`, `TransferProposed`, `JobCompleted`, …) are written to an `outbox_event` table inside the same DB transaction as the state change. Atomicity is preserved without distributed transactions.
- **`@Scheduled` poller** drains the outbox and dispatches to in-process listeners. Same outbox can later feed Kafka without code change at the publisher side.
- **In-app `Notification` inbox** — every event the user cares about lands here. Source of truth.
- **OS-level push** via FCM (Android) and APNS (iOS) for time-sensitive events. Web push is a future concern.
- **`GET /notifications`** / **`POST /notifications/{id}/read`** — paginated inbox + read flag.
- **`POST /device-tokens`** / **`DELETE /device-tokens/{id}`** — register / unregister a device.

### 3.6 Ledger & commissions

- **Ledger-only payments in v1.** No real money movement. Stripe Connect can land later behind a port without schema change.
- **One `LedgerAccount` per user.** Balance stored as `BIGINT` cents — never `float`/`double` for money.
- **Double-entry `LedgerEntry`** rows — every transaction is two paired immutable lines (`correlation_id`), one debit + one credit.
- **On `CLOSED_PAID`** — commissions are walked up the transfer chain. Each link's `commission_pct` (`BigDecimal`) takes a cut from the final payout; the executing provider gets the remainder.
- **`GET /ledger/balance`** — current balance for the caller.
- **`GET /ledger/entries?since=…`** — paginated history.

### 3.7 Ratings

- **`POST /jobs/{id}/rating`** — submit a 1–5 score + optional comment. Allowed only on `CLOSED_PAID` jobs.
- **Bidirectional, reveal-after-both.** Each side's rating is hidden from the other until both have rated, OR a TTL expires (default 14 days). Mitigates retaliation bias.
- **`GET /users/{id}/ratings`** — public aggregate (count + average) only. Individual ratings are not exposed.

### 3.8 Cross-cutting platform features

- **API versioning** — every endpoint is prefixed `/api/v1/`. Breaking changes bump the prefix so older mobile installs (which can't be force-updated) keep working.
- **RFC 7807 errors** — every 4xx/5xx is `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, and a domain `code`. The frontend parses one shape.
- **Idempotency keys** — `Idempotency-Key` header required on every mutating endpoint. Per-(user, key) cached response for 24h. Safe for mobile retries on flaky networks.
- **Rate limiting** — Bucket4j, keyed on the JWT subject claim (NOT IP — a school of mobile users behind one NAT looks like one IP).
- **Observability** — structured JSON logs (SLF4J), Spring Boot Actuator health endpoints, request tracing via correlation IDs, metrics for state-transition rates and outbox lag.
- **CORS** — closed allow-list (deployed web origin + `localhost:5173` for dev). Never `*`. Mobile apps don't send `Origin`, so this only constrains web.
- **HTTPS at the boundary** in production — terminated by the load balancer, not Spring Boot.

---

## 4. Architectural guardrails (locked-in decisions)

1. **DTOs at every HTTP boundary.** A JPA entity is never serialized. MapStruct handles the conversion.
2. **Fat services, thin controllers.** Controllers parse, validate, delegate, respond. Business logic in a controller is a bug.
3. **`@Transactional(REQUIRED)`** on every mutating service method. Read-only methods use `readOnly = true`.
4. **Flyway only.** No `ddl-auto=update`. Migrations are immutable once pushed.
5. **BigDecimal or BIGINT cents for money.** Never `float`/`double`.
6. **Enforce masking server-side.** The frontend never receives data it isn't authorized to render.
7. **404, not 403, for ownership violations.** Prevents enumerating other users' ids.
8. **Hibernate auditing uses `Instant`**, not `OffsetDateTime` (`@CreatedDate` doesn't support the latter).
9. **Conventional Commits, atomic, on feature branches.** Never directly to `main`.

---

## 5. What's explicitly OUT of v1

- File / photo attachments on jobs.
- Real money movement (Stripe Connect — adapter port reserved, implementation deferred).
- `Company` entity (independent contractors only — schema leaves room for `company_id` later).
- Web push notifications (mobile push only via FCM/APNS).
- Multi-tenancy / multi-region.
- Bilingual content (Hebrew/English) — `service_category_translations` table is a future slice.
