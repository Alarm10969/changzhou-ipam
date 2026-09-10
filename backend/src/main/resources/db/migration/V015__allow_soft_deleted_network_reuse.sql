-- Soft deletion must release business keys for a later correction/rebuild, while
-- retaining the original rows for audit history. V001 table-level UNIQUE
-- constraints continue to apply after deleted_at is populated, so this migration
-- replaces them with visible-row unique indexes.

CREATE TABLE outlet_v015 (
  id INTEGER PRIMARY KEY,
  branch_id INTEGER NOT NULL,
  code TEXT NOT NULL COLLATE NOCASE CHECK(length(code) BETWEEN 1 AND 32),
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  deleted_at TEXT,
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON DELETE RESTRICT
);
INSERT INTO outlet_v015(id,branch_id,code,name,is_active,created_at,updated_at,deleted_at)
  SELECT id,branch_id,code,name,is_active,created_at,updated_at,deleted_at FROM outlet;
DROP TABLE outlet;
ALTER TABLE outlet_v015 RENAME TO outlet;
CREATE INDEX idx_outlet_branch_active ON outlet(branch_id,is_active,name);
CREATE INDEX idx_outlet_visible ON outlet(deleted_at,is_active,branch_id,name);
CREATE UNIQUE INDEX uk_outlet_branch_code_visible ON outlet(branch_id,code) WHERE deleted_at IS NULL;

CREATE TABLE ip_range_v015 (
  id INTEGER PRIMARY KEY,
  branch_id INTEGER NOT NULL,
  cidr TEXT NOT NULL CHECK(length(cidr) BETWEEN 7 AND 18),
  subnet_mask TEXT NOT NULL,
  gateway TEXT NOT NULL,
  dns_primary TEXT NOT NULL,
  dns_secondary TEXT,
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  outlet_id INTEGER REFERENCES outlet(id) ON DELETE RESTRICT,
  deleted_at TEXT,
  range_type TEXT,
  planning_group TEXT,
  rule_version_id INTEGER,
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON DELETE RESTRICT
);
INSERT INTO ip_range_v015(id,branch_id,cidr,subnet_mask,gateway,dns_primary,dns_secondary,is_active,created_at,updated_at,outlet_id,deleted_at,range_type,planning_group,rule_version_id)
  SELECT id,branch_id,cidr,subnet_mask,gateway,dns_primary,dns_secondary,is_active,created_at,updated_at,outlet_id,deleted_at,range_type,planning_group,rule_version_id FROM ip_range;
DROP TABLE ip_range;
ALTER TABLE ip_range_v015 RENAME TO ip_range;
CREATE INDEX idx_ip_range_outlet_active ON ip_range(outlet_id,is_active,cidr);
CREATE INDEX idx_ip_range_visible ON ip_range(deleted_at,is_active,outlet_id,cidr);
CREATE UNIQUE INDEX uk_ip_range_cidr_visible ON ip_range(cidr) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_ip_range_outlet_type_active ON ip_range(outlet_id,range_type) WHERE outlet_id IS NOT NULL AND deleted_at IS NULL AND range_type IS NOT NULL;
CREATE INDEX idx_ip_range_planning_group ON ip_range(outlet_id,planning_group,range_type);

CREATE TABLE ip_address_pool_v015 (
  id INTEGER PRIMARY KEY,
  ip_range_id INTEGER NOT NULL,
  ip_address TEXT NOT NULL,
  is_reserved INTEGER NOT NULL DEFAULT 0 CHECK(is_reserved IN (0,1)),
  reservation_reason TEXT,
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  deleted_at TEXT,
  FOREIGN KEY(ip_range_id) REFERENCES ip_range(id) ON DELETE RESTRICT
);
INSERT INTO ip_address_pool_v015(id,ip_range_id,ip_address,is_reserved,reservation_reason,is_active,created_at,updated_at,deleted_at)
  SELECT id,ip_range_id,ip_address,is_reserved,reservation_reason,is_active,created_at,updated_at,deleted_at FROM ip_address_pool;
DROP TABLE ip_address_pool;
ALTER TABLE ip_address_pool_v015 RENAME TO ip_address_pool;
CREATE INDEX idx_ip_pool_range_available ON ip_address_pool(ip_range_id,is_active,is_reserved,ip_address);
CREATE INDEX idx_ip_pool_visible ON ip_address_pool(deleted_at,ip_range_id,is_active,is_reserved,ip_address);
CREATE UNIQUE INDEX uk_ip_pool_address_visible ON ip_address_pool(ip_address) WHERE deleted_at IS NULL;
