---
name: add-flyway-migration
description: Use when adding a new database table or schema change to the Lead Manager backend. Covers the immutable Flyway V{n}__*.sql file naming, schema conventions (snake_case, audit columns, identity PKs, optimistic-lock version, functional indexes), and the BaseEntity column contract every table must match. Trigger when the user asks to add a table, add a column, change the schema, or whenever a new entity is being introduced.
---

# Adding a Flyway migration in the Lead Manager backend

## Where it lives

- File path: `src/main/resources/db/migration/V{n}__short_description.sql`
- `{n}` is the next integer after the highest existing version. **Never reuse or edit a migration once committed to a branch that has been pushed** — migrations are immutable per CLAUDE.md.
- Naming: lowercase snake_case, double underscore after the version. E.g. `V5__create_service_areas.sql`.

## Mandatory shape

Every domain table mirrors what `BaseEntity` (in `com.leadmanager.api.common.audit`) expects:

```sql
CREATE TABLE my_table (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- domain columns here (snake_case) ...
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);
```

Rationale:
- `BIGINT IDENTITY` — Postgres-native sequence, matches `@GeneratedValue(strategy = IDENTITY)` in `BaseEntity`.
- `TIMESTAMPTZ` with `DEFAULT now()` — Spring Data JPA Auditing populates these via `Instant`, and the SQL default is a safety net for any direct SQL inserts (test fixtures, future seed scripts).
- `version BIGINT NOT NULL DEFAULT 0` — `@Version` optimistic lock counter. Without this column, Hibernate throws on save().

## Indexes

- **Unique constraints** that need case-insensitive matching use a **functional unique index**, not the column-level UNIQUE:
  ```sql
  CREATE UNIQUE INDEX my_table_email_lower_uq ON my_table (LOWER(email));
  ```
  Repository queries MUST also use `LOWER(...)` so they hit the functional index.
- **Partial indexes** for "active rows only" queries:
  ```sql
  CREATE INDEX my_table_active_by_user_ix
      ON my_table (user_id, expires_at)
      WHERE revoked_at IS NULL;
  ```
- Indexes are named `{table}_{cols-or-purpose}_{suffix}` where suffix is `pk`, `uq`, `ix`, `fk`.

## Foreign keys

- Always name them explicitly: `CONSTRAINT fk_{child}_{parent} FOREIGN KEY (...) REFERENCES ...`.
- Decide ON DELETE behaviour deliberately. For owned children (e.g. refresh_tokens belong to users), `ON DELETE CASCADE` is correct. For peer references (e.g. job.assignee_user_id), `ON DELETE RESTRICT` (the default) keeps the data consistent.
- Self-referencing FKs (rotation chains, parent/child trees) are fine — see `V4__create_refresh_tokens.sql` for the `replaced_by_id` pattern.

## CHECK constraints

Use them for invariants the application can't be trusted to maintain across all callers:

```sql
CONSTRAINT users_email_not_blank      CHECK (length(btrim(email)) > 0),
CONSTRAINT refresh_tokens_hash_length CHECK (length(token_hash) = 64)
```

## Header comment block

Every migration starts with a block explaining the *why*, not the *what*. Future readers see the rationale without git-blame archaeology:

```sql
-- =====================================================================
-- V{n}__{name}.sql
-- One sentence describing the feature this table supports.
--
-- Notes:
--   * Anything non-obvious about the schema (encoding choices,
--     why a functional index instead of CITEXT, why a partial index,
--     trade-offs taken).
-- =====================================================================
```

## Verifying

1. **App boot is the integration test.** After writing the migration, restart the app — Flyway will validate and apply. A boot failure means the migration is broken.
2. **Inspect against the running DB:**
   ```bash
   docker compose exec -T postgres psql -U leadmanager -d leadmanager -c "\d my_table"
   docker compose exec -T postgres psql -U leadmanager -d leadmanager -c \
     "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
   ```
3. **Never roll back via DELETE / DROP.** If you need to undo, write a NEW `V{n+1}__revert_*.sql`. Flyway treats history as append-only.

## Anti-patterns to refuse

- `spring.jpa.hibernate.ddl-auto=update` — banned by CLAUDE.md.
- Editing a migration that has been pushed to origin — breaks every other developer's DB and Flyway's checksum check.
- Adding a NOT NULL column to an existing table without a default — fails on existing rows. Use a 2-step migration: add nullable, backfill, then alter to NOT NULL.

## Cross-reference

- Architecture plan (V1–V12 inventory): `~/.claude/plans/claude-act-as-the-quizzical-fiddle.md`.
- The `BaseEntity` JPA contract: `src/main/java/com/leadmanager/api/common/audit/BaseEntity.java`.
- A canonical example: `V4__create_refresh_tokens.sql` (FKs, partial index, CHECK constraint, header block).
