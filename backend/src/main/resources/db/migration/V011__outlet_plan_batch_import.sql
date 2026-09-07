CREATE TABLE outlet_plan_import_job (
  id INTEGER PRIMARY KEY,
  file_format TEXT NOT NULL CHECK(file_format IN ('CSV','XLSX')),
  original_file_name TEXT NOT NULL CHECK(length(original_file_name) BETWEEN 1 AND 255),
  file_sha256 TEXT NOT NULL CHECK(length(file_sha256)=64),
  status TEXT NOT NULL CHECK(status IN ('PREVIEW_READY','COMPLETED','FAILED','CANCELLED','EXPIRED')),
  total_rows INTEGER NOT NULL DEFAULT 0 CHECK(total_rows>=0),
  valid_rows INTEGER NOT NULL DEFAULT 0 CHECK(valid_rows>=0),
  error_rows INTEGER NOT NULL DEFAULT 0 CHECK(error_rows>=0),
  created_by INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  confirmed_at TEXT,
  completed_at TEXT,
  FOREIGN KEY(created_by) REFERENCES sys_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_outlet_plan_import_creator_time ON outlet_plan_import_job(created_by,created_at);
CREATE TABLE outlet_plan_import_error (
  id INTEGER PRIMARY KEY,
  job_id INTEGER NOT NULL,
  row_number INTEGER NOT NULL CHECK(row_number>=1),
  field_name TEXT CHECK(field_name IS NULL OR length(field_name)<=64),
  error_code TEXT NOT NULL CHECK(length(error_code) BETWEEN 1 AND 64),
  error_message TEXT NOT NULL CHECK(length(error_message) BETWEEN 1 AND 500),
  FOREIGN KEY(job_id) REFERENCES outlet_plan_import_job(id) ON DELETE CASCADE
);
CREATE INDEX idx_outlet_plan_import_error_job_row ON outlet_plan_import_error(job_id,row_number);
