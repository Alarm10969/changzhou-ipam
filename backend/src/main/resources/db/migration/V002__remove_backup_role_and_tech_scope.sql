UPDATE sys_user
SET role = 'TECH_ADMIN', branch_id = NULL, outlet_id = NULL, parent_user_id = NULL, must_change_password = 0
WHERE role = 'TECH_ADMIN_BACKUP';

UPDATE sys_user
SET branch_id = NULL, outlet_id = NULL, parent_user_id = NULL, must_change_password = 0
WHERE role = 'TECH_ADMIN';
