-- Independent outlet-directory data used for display and lookup only.  It deliberately
-- does not replace the operational outlet table used by IP approval and allocation.
CREATE TABLE outlet_directory (
  id INTEGER PRIMARY KEY,
  managed_outlet_no TEXT NOT NULL UNIQUE CHECK(length(managed_outlet_no) BETWEEN 1 AND 64),
  outlet_no TEXT NOT NULL CHECK(length(outlet_no) BETWEEN 1 AND 64),
  org_code TEXT NOT NULL CHECK(length(org_code) BETWEEN 1 AND 64),
  outlet_name TEXT NOT NULL CHECK(length(outlet_name) BETWEEN 1 AND 100),
  outlet_address TEXT CHECK(outlet_address IS NULL OR length(outlet_address) <= 200),
  phone TEXT CHECK(phone IS NULL OR length(phone) <= 32),
  branch_id INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON DELETE RESTRICT
);
CREATE INDEX idx_outlet_directory_branch_name ON outlet_directory(branch_id,outlet_name);
CREATE INDEX idx_outlet_directory_outlet_no ON outlet_directory(outlet_no);
CREATE INDEX idx_outlet_directory_org_code ON outlet_directory(org_code);
