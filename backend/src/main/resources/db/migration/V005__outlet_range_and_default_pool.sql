ALTER TABLE ip_range ADD COLUMN outlet_id INTEGER REFERENCES outlet(id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uk_ip_range_outlet ON ip_range(outlet_id) WHERE outlet_id IS NOT NULL;
CREATE INDEX idx_ip_range_outlet_active ON ip_range(outlet_id,is_active,cidr);
