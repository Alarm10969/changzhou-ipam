ALTER TABLE outlet_directory ADD COLUMN flat_status TEXT;
ALTER TABLE outlet_directory ADD COLUMN line1_carrier TEXT;
ALTER TABLE outlet_directory ADD COLUMN line1_bandwidth TEXT;
ALTER TABLE outlet_directory ADD COLUMN line1_fee TEXT;
ALTER TABLE outlet_directory ADD COLUMN line2_carrier TEXT;
ALTER TABLE outlet_directory ADD COLUMN line2_bandwidth TEXT;
ALTER TABLE outlet_directory ADD COLUMN line2_fee TEXT;
CREATE TABLE directory_import_job (
 id TEXT PRIMARY KEY,
 actor_id INTEGER NOT NULL REFERENCES sys_user(id),
 created_at INTEGER NOT NULL,
 records_json TEXT NOT NULL,
 preview_json TEXT NOT NULL,
 status TEXT NOT NULL DEFAULT 'READY'
);
