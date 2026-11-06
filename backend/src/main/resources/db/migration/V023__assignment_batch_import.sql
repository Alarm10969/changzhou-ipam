CREATE TABLE assignment_import_job (
  id INTEGER PRIMARY KEY,
  original_file_name TEXT NOT NULL CHECK(length(original_file_name) BETWEEN 1 AND 255),
  file_format TEXT NOT NULL CHECK(file_format IN ('CSV','XLSX')),
  file_sha256 TEXT NOT NULL CHECK(length(file_sha256)=64),
  status TEXT NOT NULL CHECK(status IN ('PREVIEW_READY','COMPLETED','CANCELLED','EXPIRED')),
  preview_version INTEGER NOT NULL DEFAULT 1 CHECK(preview_version>=1),
  created_by INTEGER NOT NULL REFERENCES sys_user(id) ON DELETE RESTRICT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  confirmed_at TEXT,
  completed_at TEXT
);
CREATE INDEX idx_assignment_import_creator_time ON assignment_import_job(created_by,created_at);
CREATE TABLE assignment_import_row (
  id INTEGER PRIMARY KEY,
  job_id INTEGER NOT NULL REFERENCES assignment_import_job(id) ON DELETE CASCADE,
  row_number INTEGER NOT NULL CHECK(row_number>=2),
  raw_json TEXT NOT NULL,
  snapshot_json TEXT NOT NULL,
  result TEXT NOT NULL CHECK(result IN ('READY','DUPLICATE','ERROR','IMPORTED')),
  error_code TEXT NOT NULL,
  error_message TEXT NOT NULL,
  assignment_id INTEGER REFERENCES ip_assignment(id) ON DELETE RESTRICT,
  UNIQUE(job_id,row_number)
);
CREATE INDEX idx_assignment_import_row_job ON assignment_import_row(job_id,row_number);
