---
name: lead-manager-vertical-slice
description: Use when starting any new backend slice in the Lead Manager project (a new domain feature like Jobs, Transfers, Ratings). Provides the exact file order, the cadence of sub-step pauses, the conventions for entities/services/controllers/tests, and the commit hygiene the user expects. Trigger when the user says "start slice N", "let's build the {feature} slice", "next slice", or similar.
---

# Building a vertical slice in the Lead Manager backend

This is the recipe **every** future slice follows. Project context: see `CLAUDE.md` and the architecture plan at `~/.claude/plans/claude-act-as-the-quizzical-fiddle.md`.

## Behavioral rules (do not skip)

1. **Small, supervised steps.** Subdivide every slice into 3–5 sub-steps with an explicit pause point between each. After each sub-step: stop, summarize what changed in 3–5 bullets, wait for user acknowledgement before continuing. Reason: the user is the human reviewer and a full slice in one shot is too much surface area.
2. **Branch first.** `git checkout -b <branch>` off `backend`. Names per `CLAUDE.md`: `feat/...`, `fix/...`, `chore/...`, `refactor/...`.
3. **TDD where it makes sense.** Service-tier business logic: tests first. Controllers and entities: tests in the same sub-step that introduces them.
4. **One concern per file.** Configuration beans in their own `@Configuration`. Services have an interface + impl pair. Commands/queries are immutable records distinct from entities and DTOs.
5. **Atomic Conventional Commits.** `type(scope): description`. Don't bundle unrelated changes. Each sub-step is one or a few commits; never let two concerns share a commit.

## Sub-step ordering for a typical slice

| Sub-step | Files | What lands |
|---|---|---|
| **a — data layer** | `V{n}__create_X.sql`, `X.java` (entity), `XRepository.java` | Schema + JPA. Compiles, smoke test still passes. No HTTP. |
| **b — service tier** | `XCommand.java` (record), `XService.java` (interface), `XServiceImpl.java`, `XServiceImplTest.java` | Pure business logic + Mockito unit tests. Still no HTTP. |
| **c — HTTP face** | `web/XRequest.java`, `web/XResponse.java`, `web/XMapper.java`, `web/XController.java`, `web/XControllerTest.java` (`@WebMvcTest` + real `SecurityConfig`) | Endpoint live. End-to-end flow verifiable with curl. |
| **d (if needed) — auth/protection** | Update `SecurityConfig` rules; add `@PreAuthorize` bean checks | Granular AuthZ for the new endpoints. |
| **e (if needed) — events / async** | Outbox writes, event listeners | Reactive consequences of the new feature. |

If the slice is small (≤3 sub-steps), inline a–b–c. If big (e.g. Jobs has state machine + transitions + masking), break c further.

## Per-file conventions

### Entity
- `@Entity @Table(name="snake_case")`, extend `BaseEntity` (provides id, version, created_at, updated_at).
- Lombok: `@Getter`, targeted `@Setter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@Builder` on a private constructor. **NEVER `@Data`** on a JPA entity.
- Audit timestamps MUST be `java.time.Instant` (Spring Data JPA Auditing does NOT support `OffsetDateTime` — see [[feedback-jpa-auditing-uses-instant]]).
- Immutable fields (e.g. `email`) have getters but no setters.
- Encapsulate predicates and state transitions as methods on the entity (`isActive(now)`, `revoke(when)`) so callers don't reinvent them.

### Repository
- `JpaRepository<X, Long>`. `@Repository`.
- Queries that need case-insensitive matching MUST go through `LOWER(...)` so they hit functional unique indexes (validated pattern from sub-step 1.1).
- Bulk updates use `@Modifying @Query("UPDATE ...")` inside a write transaction.

### Service
- Interface in the feature package, JavaDoc on the contract (the *why* lives here per CLAUDE.md).
- `@Service` implementation, constructor injection only.
- `@Transactional(propagation = REQUIRED)` on every mutating method. `readOnly = true` for pure reads.
- Optimistic concurrency via `@Version`. Pessimistic via `SELECT FOR UPDATE` in state-machine transitions.
- Domain input via `XCommand` immutable record — NEVER take HTTP DTOs.
- Domain output: the entity (mapper turns it into the response DTO at the boundary) or another record.
- Throw `ApiException(ErrorCode.X, "detail")` for business errors. Generic detail messages; never leak which email exists, etc.

### HTTP layer
- DTOs in `web/` sub-package. Records with `jakarta.validation` annotations on input.
- `@RestController @RequestMapping(ApiVersion.V1 + "/...")`. Constructor inject deps.
- Each controller method does exactly four things: parse, convert, delegate, respond. Business logic in a controller is a bug.
- MapStruct mapper: `@Mapper` interface, generated impl in `target/generated-sources/annotations/.../XMapperImpl.java`. `unmappedTargetPolicy=ERROR` will fail the build on missed fields — use `@Mapping(source=..., target=...)` for renames.

### Tests
- Service-tier: Mockito unit tests, no Spring, no DB.
- Controller-tier: `@WebMvcTest` + `@Import({SecurityConfig.class, JwtAuthFilter.class, JwtAuthenticationEntryPoint.class, ...MapperImpl.class, GlobalExceptionHandler.class})`. `@MockBean JwtService` (the chain needs it but the test doesn't care). Cover: 401-no-token, 401-bad-token, 200 happy, 400 validation, and the business 4xx paths.
- Persistence-tier: `@SpringBootTest` + Testcontainers `@ServiceConnection` (already the pattern in `LeadManagerApplicationTests`).
- Run after every sub-step: `./mvnw test -Dtest='Test1,Test2,...'` (the full `./mvnw test` may hit Docker pull flakes if Docker isn't warm).

## Run the app live after each sub-step

For HTTP changes, restart the running app and curl the endpoint. The user values seeing it work, not just green tests:

```bash
# Free port 8080 (Windows-only orphan JVM gotcha — see debug-spring-boot-port skill)
netstat -ano | grep ':8080' | grep LISTENING | awk '{print $5}' | head -1 | xargs -I {} taskkill //PID {} //F

cd "<project root>"
set -a && . ./.env && set +a && ./mvnw -B spring-boot:run   # use run_in_background=true
# wait for it: until curl -sf localhost:8080/actuator/health 2>/dev/null | grep -q UP; do sleep 2; done
```

## Push policy

**Local only by default.** Only push (or open a PR) when the user explicitly asks. Branch lives on the user's machine until they say "push" or "open PR".

## Related skills

- `add-flyway-migration` — for the V{n}__*.sql file conventions.
- `add-rfc7807-error` — for new `ErrorCode` entries and exception mapping.
- `debug-spring-boot-port` — for the Windows orphan-JVM-on-8080 recovery.
