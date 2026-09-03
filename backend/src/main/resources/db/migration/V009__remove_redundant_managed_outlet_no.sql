-- 管理网点号等同于所属支行编号，不再作为网点信息的重复字段保存。
CREATE TABLE outlet_directory_v009 (
  id INTEGER PRIMARY KEY,
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
INSERT INTO outlet_directory_v009(id,outlet_no,org_code,outlet_name,outlet_address,phone,branch_id,created_at,updated_at)
  SELECT id,outlet_no,org_code,outlet_name,outlet_address,phone,branch_id,created_at,updated_at FROM outlet_directory;
DROP TABLE outlet_directory;
ALTER TABLE outlet_directory_v009 RENAME TO outlet_directory;
CREATE INDEX idx_outlet_directory_branch_name ON outlet_directory(branch_id,outlet_name);
CREATE INDEX idx_outlet_directory_outlet_no ON outlet_directory(outlet_no);
CREATE INDEX idx_outlet_directory_org_code ON outlet_directory(org_code);
