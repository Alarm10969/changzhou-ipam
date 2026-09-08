-- The planning block is derived for each outlet from its production CIDR and
-- the configured maximum segment offset.  The historical default columns stay
-- available for rule-version audit, but no longer constrain outlet plans.
-- SQLite performs the compatible table rebuild internally for DROP COLUMN.
ALTER TABLE ip_application DROP COLUMN override_reason;
