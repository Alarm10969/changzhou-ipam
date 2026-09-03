ALTER TABLE sys_user ADD COLUMN is_initial_admin INTEGER NOT NULL DEFAULT 0 CHECK(is_initial_admin IN (0,1));
ALTER TABLE sys_user ADD COLUMN deleted_at TEXT NULL;
UPDATE sys_user SET is_initial_admin=1,is_active=1,deleted_at=NULL WHERE role='ADMIN';
CREATE UNIQUE INDEX uk_sys_user_single_initial_admin ON sys_user(is_initial_admin) WHERE is_initial_admin=1;
CREATE INDEX idx_sys_user_role_state ON sys_user(role,is_active,deleted_at);
CREATE TRIGGER trg_initial_admin_no_delete BEFORE DELETE ON sys_user WHEN OLD.is_initial_admin=1 BEGIN SELECT RAISE(ABORT,'INITIAL_ADMIN_PROTECTED'); END;
CREATE TRIGGER trg_initial_admin_no_disable_or_demote BEFORE UPDATE ON sys_user WHEN OLD.is_initial_admin=1 AND (NEW.is_initial_admin<>1 OR NEW.role<>'ADMIN' OR NEW.is_active<>1 OR NEW.deleted_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'INITIAL_ADMIN_PROTECTED'); END;
