-- =====================================================================
-- V1__baseline.sql
-- Flyway baseline for the lead-manager schema.
--
-- Migration policy (see CLAUDE.md):
--   * EVERY schema change ships as a new V{n}__description.sql file.
--   * Once a migration is merged to main, it is IMMUTABLE — never edit it.
--   * Hibernate's ddl-auto is set to "validate"; this file is the only
--     source of truth for the database schema.
--   * Use snake_case for tables and columns.
--
-- The first domain table (users) will land in V2__create_users.sql.
-- =====================================================================

-- No DDL yet; this baseline reserves V1 so the first feature slice can
-- ship cleanly as V2 without renumbering history.
SELECT 1;
