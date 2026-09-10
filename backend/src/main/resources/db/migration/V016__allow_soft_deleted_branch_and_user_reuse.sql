-- Deleted branches and user accounts are retained for audit, but their business
-- identifiers must be available to replacement records. Rebuild the V001 tables
-- whose inline UNIQUE constraints cannot express that soft-delete rule.

CREATE TABLE branch_v016 (
  id INTEGER PRIMARY KEY,
  code TEXT NOT NULL COLLATE NOCASE CHECK(length(code) BETWEEN 1 AND 32),
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  deleted_at TEXT
);
INSERT INTO branch_v016(id,code,name,is_active,created_at,updated_at,deleted_at)
  SELECT id,code,name,is_active,created_at,updated_at,deleted_at FROM branch;
DROP TABLE branch;
ALTER TABLE branch_v016 RENAME TO branch;
CREATE INDEX idx_branch_visible ON branch(deleted_at,is_active,name);
CREATE UNIQUE INDEX uk_branch_code_visible ON branch(code) WHERE deleted_at IS NULL;

CREATE TABLE sys_user_v016 (
  id INTEGER PRIMARY KEY,
  username TEXT NOT NULL COLLATE NOCASE CHECK(length(username) BETWEEN 3 AND 64),
  password_hash TEXT NOT NULL,
  real_name TEXT NOT NULL CHECK(length(real_name) BETWEEN 1 AND 100),
  role TEXT NOT NULL CHECK(role IN ('ADMIN','TECH_ADMIN')),
  branch_id INTEGER,
  outlet_id INTEGER,
  parent_user_id INTEGER UNIQUE,
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  must_change_password INTEGER NOT NULL DEFAULT 1 CHECK(must_change_password IN (0,1)),
  failed_login_count INTEGER NOT NULL DEFAULT 0,
  locked_until TEXT,
  password_changed_at TEXT,
  last_login_at TEXT,
  created_by INTEGER,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  version INTEGER NOT NULL DEFAULT 0,
  is_initial_admin INTEGER NOT NULL DEFAULT 0 CHECK(is_initial_admin IN (0,1)),
  deleted_at TEXT,
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON DELETE RESTRICT,
  FOREIGN KEY(outlet_id) REFERENCES outlet(id) ON DELETE RESTRICT,
  FOREIGN KEY(parent_user_id) REFERENCES sys_user_v016(id) ON DELETE RESTRICT,
  FOREIGN KEY(created_by) REFERENCES sys_user_v016(id) ON DELETE RESTRICT
);
INSERT INTO sys_user_v016(id,username,password_hash,real_name,role,branch_id,outlet_id,parent_user_id,is_active,must_change_password,failed_login_count,locked_until,password_changed_at,last_login_at,created_by,created_at,updated_at,version,is_initial_admin,deleted_at)
  SELECT id,username,password_hash,real_name,role,branch_id,outlet_id,parent_user_id,is_active,must_change_password,failed_login_count,locked_until,password_changed_at,last_login_at,created_by,created_at,updated_at,version,is_initial_admin,deleted_at FROM sys_user;
DROP TABLE sys_user;
ALTER TABLE sys_user_v016 RENAME TO sys_user;
CREATE INDEX idx_user_scope ON sys_user(branch_id,outlet_id,role,is_active);
CREATE UNIQUE INDEX uk_sys_user_single_initial_admin ON sys_user(is_initial_admin) WHERE is_initial_admin=1;
CREATE INDEX idx_sys_user_role_state ON sys_user(role,is_active,deleted_at);
CREATE UNIQUE INDEX uk_sys_user_username_visible ON sys_user(username) WHERE deleted_at IS NULL;
CREATE TRIGGER trg_initial_admin_no_delete BEFORE DELETE ON sys_user WHEN OLD.is_initial_admin=1 BEGIN SELECT RAISE(ABORT,'INITIAL_ADMIN_PROTECTED'); END;
CREATE TRIGGER trg_initial_admin_no_disable_or_demote BEFORE UPDATE ON sys_user WHEN OLD.is_initial_admin=1 AND (NEW.is_initial_admin<>1 OR NEW.role<>'ADMIN' OR NEW.is_active<>1 OR NEW.deleted_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'INITIAL_ADMIN_PROTECTED'); END;
