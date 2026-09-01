ALTER TABLE categories
    ADD COLUMN age_restricted BOOLEAN NOT NULL DEFAULT false;

INSERT INTO categories (slug, name, sort_order, active, age_restricted)
VALUES ('adult', 'Adult (18+)', 999, true, true);
