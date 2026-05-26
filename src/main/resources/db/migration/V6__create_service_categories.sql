-- =====================================================================
-- V6__create_service_categories.sql
-- Reference table of trades (HVAC, plumbing, electrical, ...) that
-- providers will pick from on registration / job creation.
--
-- Notes:
--   * Reference data: rows are versioned with the application via Flyway,
--     never written by application code. Adding a trade = adding a new
--     V{n} migration with an INSERT.
--   * `code` is the stable lowercase snake_case machine identifier other
--     tables will FK into. A CHECK constraint enforces the snake_case
--     shape so a stray UPDATE can't violate the convention.
--   * `active` is a soft-delete flag. Categories are NEVER deleted because
--     historical jobs still reference them; toggling active=false just
--     hides the trade from new selections.
--   * `sort_order` uses multiples of 10 so a later migration can insert
--     a new trade ("appliance_repair_premium" → 11) without renumbering
--     the rest of the table.
--   * Display labels are seeded in English only for now; bilingual
--     (he/en) labels will land in a later slice via a sibling
--     service_category_translations table — not by widening this one.
-- =====================================================================

CREATE TABLE service_categories (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT service_categories_code_uq      UNIQUE (code),
    CONSTRAINT service_categories_code_format  CHECK (code ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT service_categories_display_name_not_blank
        CHECK (length(btrim(display_name)) > 0)
);

-- Composite index supporting the "active categories ordered for display"
-- query (the only access pattern the public endpoint needs).
CREATE INDEX service_categories_active_sort_ix
    ON service_categories (active, sort_order, code);

-- Seed: the 12 trades the marketplace launches with, ordered alphabetically
-- by display name. Adjust labels (or add new trades) via a later migration,
-- never by application code.
INSERT INTO service_categories (code, display_name, sort_order) VALUES
    ('appliance_repair', 'Appliance Repair',  10),
    ('cleaning',         'Cleaning',          20),
    ('computer_repair',  'Computer Repair',   30),
    ('electrical',       'Electrical',        40),
    ('gardening',        'Gardening',         50),
    ('handyman',         'Handyman',          60),
    ('hvac',             'HVAC',              70),
    ('locksmith',        'Locksmith',         80),
    ('moving',           'Moving',            90),
    ('painting',         'Painting',         100),
    ('pest_control',     'Pest Control',     110),
    ('plumbing',         'Plumbing',         120);
