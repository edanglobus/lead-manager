# Service Field Lead Manager & Marketplace — Backend

A peer-to-peer job marketplace for independent service providers. Users generate
leads (Jobs), execute them, or transfer them to other users for a commission.

This module is the **Spring Boot 3 / Java 21 backend API**. The mobile (React
Native / Expo) and web (React) clients live in their own packages — they will
be added once the backend's first vertical slices are in place.

> Project rules of engagement live in [CLAUDE.md](CLAUDE.md). Read it once
> before touching code. The short version: **DTOs at the boundary, BigDecimal
> for money, RFC 7807 for errors, Flyway for the schema, no commits to `main`,
> Conventional Commits everywhere.**

---

## Prerequisites

| Tool           | Version | Notes                                       |
|----------------|---------|---------------------------------------------|
| JDK            | 21      | LTS. `java -version` must report 21.x.      |
| Docker Desktop | latest  | Powers local Postgres and Testcontainers.   |
| Git            | latest  | Conventional Commits + feature branches.    |

Maven is **not** required globally — the project ships a Maven wrapper (`./mvnw`).

---

## First-time setup

```bash
# 1. Copy env template and pick a local DB password.
cp .env.example .env
# then edit .env and set DB_PASSWORD to a non-default value

# 2. Boot Postgres.
docker compose up -d postgres
docker compose ps          # should report "healthy"

# 3. Compile and run tests (Testcontainers spins its own Postgres for tests).
./mvnw clean verify

# 4. Start the API.
./mvnw spring-boot:run
```

The API listens on `http://localhost:8080`.

### Health check

```bash
curl http://localhost:8080/actuator/health
# => {"status":"UP"}
```

### Error contract (RFC 7807)

Any error response is `application/problem+json` with this shape:

```json
{
  "type": "https://leadmanager.com/errors/resource-not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "Job 42 does not exist",
  "instance": "/api/v1/jobs/42",
  "code": "RESOURCE_NOT_FOUND"
}
```

Stack traces are **never** included.

---

## Project layout

```
src/main/java/com/leadmanager/api/
  LeadManagerApplication.java   # entry point
  common/
    api/        ApiVersion       # /api/v1 prefix constant
    exception/  ApiException     # base business exception
                ErrorCode        # catalog of typed errors
                GlobalExceptionHandler  # RFC 7807 mapping
src/main/resources/
  application.yml          # default config (reads from env)
  application-local.yml    # local profile overrides
  db/migration/V*.sql      # Flyway migrations (immutable once merged)
```

Domain code (`user/`, `job/`, `transfer/`, `commission/`) is added one vertical
slice at a time — DB → Service → API → tests — never half-finished across layers.

---

## Daily commands

| Task                         | Command                            |
|------------------------------|------------------------------------|
| Compile                      | `./mvnw clean compile`             |
| Unit + integration tests     | `./mvnw test`                      |
| Full build incl. tests       | `./mvnw clean verify`              |
| Run API (local profile)      | `./mvnw spring-boot:run`           |
| Start local Postgres         | `docker compose up -d postgres`    |
| Stop local Postgres          | `docker compose down`              |
| Wipe local Postgres data     | `docker compose down -v`           |

---

## Git workflow

Per [CLAUDE.md](CLAUDE.md):

- **Branch from a feature branch, never `main`.** Current bootstrap branch: `backend`.
- **Conventional Commits** (`feat(scope): ...`, `fix(scope): ...`, `chore(scope): ...`).
- **Atomic commits.** One concern per commit. Don't bundle a migration with a UI change.
- **Compile before committing.** `./mvnw clean compile` MUST pass.

---

## What's NOT here yet

This repo currently contains only the backend skeleton. Specifically there is
no domain code, no auth, no controllers beyond `/actuator`. The next slices —
in order — are:

1. `User` entity, registration, login, JWT.
2. `Job` entity + lifecycle state machine.
3. Blind-transfer flow (masked customer data until acceptance).
4. Commission distribution + payout chain.

Each will ship with its own Flyway migration, service tests, and DTOs.
