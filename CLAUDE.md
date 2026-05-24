# Project Context: Service Field Lead Manager & Marketplace
This is a peer-to-peer job marketplace for independent service providers. Users generate leads (Jobs), execute them, or transfer them to other users for a commission. 

## Technology Stack
- **Backend:** Java 21, Spring Boot 3, Spring Data JPA
- **Database:** PostgreSQL
- **Frontend (Mobile/Web):** React Native (Expo) with TypeScript / React

## Core Business Rules (DO NOT BYPASS)
1. **Blind Transfers (Data Masking):** When User A transfers a Job to User B, User B must NOT see the `customerAddress` or `customerPhone` until they explicitly accept the commission terms. 
2. **Financial Chains:** Jobs can be transferred multiple times. When a job is marked `CLOSED_PAID`, commissions dictate payouts up the chain.
3. **Strict States:** Jobs have defined states (e.g., `OPEN_GENERAL`, `OPEN_TODAY`, `PENDING_TRANSFER`, `CLOSED_PAID`). State transitions must be strictly validated.

## Backend Architecture Rules (Spring Boot)
1. **DTOs for Everything:** Never expose raw JPA Entities through REST Controllers. Use MapStruct or manual mapping to create DTOs.
2. **Enforce Masking at the API:** The logic to hide `customerAddress` during a `PENDING_TRANSFER` state MUST occur in the Service or DTO mapping layer. The frontend should never receive masked data.
3. **Transactional Integrity:** Any operation involving state changes or commission calculations MUST be wrapped in `@Transactional(propagation = Propagation.REQUIRED)`.
4. **Currency Handling:** NEVER use `float` or `double` for money/commissions. Use `BigDecimal` or integer-based cents (e.g., `Long`).
5. **Fat Services, Thin Controllers:** Controllers should only handle HTTP routing and validation. All business logic lives in the Service layer.

## Frontend Architecture Rules (React Native / TS)
1. **Separation of Concerns:** Keep UI components purely presentational. Extract API calls and state management into custom hooks (e.g., `useJobs`, `useTransferActions`).
2. **Strict Typing:** No `any` types. Define TypeScript interfaces matching the backend DTOs exactly.
3. **Offline Resilience:** Design UI states to gracefully handle network failures (loading states, error boundaries, toast notifications for failed actions).

## AI Workflow Instructions
- **Think Before Coding:** Before generating a new JPA entity or complex business logic, output a brief design plan for approval.
- **Vertical Slices:** Implement one feature completely (DB -> Service -> API -> Frontend UI) before moving to the next.
- **Self-Correction:** After writing Java code, run `./mvnw compile` to ensure it builds before presenting the solution.

## Git Manners & Version Control
1. **Conventional Commits:** All commit messages MUST follow the Conventional Commits standard format: `type(scope): description`.
   - `feat(auth): add JWT validation for mobile`
   - `fix(job): resolve commission rounding error`
   - `chore(deps): bump spring-boot version`
   - `refactor(transfer): simplify state machine logic`
2. **Branching Strategy:** Never commit directly to `main`. Use feature branches named logically:
   - `feat/user-auth`
   - `fix/blind-transfer-bug`
   - `chore/db-migration-setup`
3. **Atomic Commits:** Commits should do one thing. Do not bundle a database schema change, a UI overhaul, and a bug fix into a single massive commit. 
4. **No Broken Builds:** Before Claude generates a commit command, it MUST ensure the code compiles (`./mvnw clean compile` for backend, `tsc --noEmit` for frontend).

## Professional Code Standards
1. **Enterprise Logging:**
   - **Backend:** NO `System.out.println`. Use SLF4J (`log.info()`, `log.error()`). Log state changes (e.g., "Job 123 transferred from User A to User B") at `INFO` level. Log all stack traces at `ERROR` level.
   - **Frontend:** NO `console.log()` left in production code. Use a dedicated logging utility that can be disabled in production or wired to a service like Sentry.
2. **Documentation (DocStrings):**
   - **Java:** All Service interfaces and complex business logic MUST have proper JavaDoc explaining the *why*, not just the *what*. Especially for commission calculations and blind transfers.
   - **TypeScript:** Use TSDoc for custom hooks and shared utility functions. 
3. **Naming Conventions:**
   - **Java/DB:** Use `camelCase` for Java variables/methods, `PascalCase` for Classes, and `snake_case` for PostgreSQL tables and columns.
   - **React Native:** Use `PascalCase` for Components, `camelCase` for functions/hooks.
4. **Magic Numbers & Strings:** Hardcoded values are forbidden. Extract job states, fee percentages, and roles to `Enums` (Java) or `const` configuration objects (TypeScript).

## AI Persona & Problem-Solving Skills
1. **Act as a Principal Engineer:** Do not just write code; architect solutions. If a requested feature introduces a security flaw, database bottleneck, or breaks the offline-first architecture, you MUST flag it and propose a safer alternative.
2. **Present Trade-offs:** When solving complex problems (e.g., syncing offline mobile data with the server), briefly explain the pros and cons of your chosen approach regarding performance, memory, and complexity.
3. **Fail Fast:** If you lack enough context to safely modify the commission calculation or job state machine, DO NOT guess. Stop and ask for clarification.

## Database Migrations & Integrity
1. **No Auto-DDL:** NEVER rely on Hibernate's `spring.jpa.hibernate.ddl-auto=update` for database schema generation in production.
2. **Migration Scripts:** All database schema changes MUST be executed via strict migration scripts (e.g., Flyway or Liquibase). When creating a new entity, simultaneously generate the corresponding `V1__create_table.sql` migration file.

## Testing & Quality Assurance (TDD Mindset)
1. **Test-First Logic:** For the Service layer—especially the commission distribution and blind transfer logic—write JUnit tests covering both happy paths and edge cases BEFORE writing the implementation. 
2. **Integration over Mocking:** For database queries, favor Testcontainers (spinning up a real Postgres instance in Docker during tests) over mocking the database layer. 
3. **Frontend Edge Cases:** React Native tests must account for "No Network" and "Slow Network" states.

## Security & API Standards
1. **API Versioning:** All backend REST endpoints must be versioned (e.g., `/api/v1/jobs`). 
2. **Standardized Errors:** The backend must return errors using the RFC 7807 "Problem Details" standard (e.g., `@ControllerAdvice` returning standard JSON with `status`, `detail`, and `instance`). Never expose raw stack traces to the frontend.
3. **Secrets Management:** NEVER hardcode API keys, JWT secrets, or database passwords. Always use environment variables (`.env`) and inject them into Spring via `application.yml` or React Native via Expo Config.
