-- A non-recommended final network requires an auditable reason.
ALTER TABLE ip_application ADD COLUMN override_reason TEXT;
