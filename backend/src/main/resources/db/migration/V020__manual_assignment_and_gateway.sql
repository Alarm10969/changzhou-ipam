CREATE TABLE ip_assignment_v020 (
  id INTEGER PRIMARY KEY,
  application_id INTEGER,
  ip_pool_id INTEGER NOT NULL,
  resource_code TEXT,
  assigned_by INTEGER NOT NULL,
  assigned_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  status TEXT NOT NULL DEFAULT 'ASSIGNED' CHECK(status IN ('ASSIGNED','RELEASED')),
  released_by INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT,
  released_at TEXT,
  release_reason TEXT CHECK(release_reason IS NULL OR length(release_reason) <= 500),
  version INTEGER NOT NULL DEFAULT 0,
  switch_port TEXT CHECK(switch_port IS NULL OR length(switch_port) <= 64),
  assignment_source TEXT NOT NULL DEFAULT 'APPLICATION' CHECK(assignment_source IN ('APPLICATION','MANUAL_REGISTRATION')),
  manual_asset_id INTEGER REFERENCES asset_definition(id) ON DELETE RESTRICT,
  manual_asset_type TEXT CHECK(manual_asset_type IS NULL OR manual_asset_type IN ('TECH','NON_TECH')),
  manual_asset_name TEXT CHECK(manual_asset_name IS NULL OR length(manual_asset_name) BETWEEN 1 AND 100),
  manual_requires_user INTEGER CHECK(manual_requires_user IS NULL OR manual_requires_user IN (0,1)),
  manual_requires_resource_code INTEGER CHECK(manual_requires_resource_code IS NULL OR manual_requires_resource_code IN (0,1)),
  manual_asset_code TEXT,
  manual_user_name TEXT,
  manual_user_unified_auth_id TEXT,
  manual_registered_by INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT,
  manual_registered_at TEXT,
  responsible_tech_user_id INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT,
  manual_note TEXT CHECK(manual_note IS NULL OR length(manual_note) <= 500),
  source_pool_state TEXT CHECK(source_pool_state IS NULL OR source_pool_state IN ('AVAILABLE','DISABLED','MANUAL_RESERVED')),
  source_is_active INTEGER CHECK(source_is_active IS NULL OR source_is_active IN (0,1)),
  source_is_reserved INTEGER CHECK(source_is_reserved IS NULL OR source_is_reserved IN (0,1)),
  source_reservation_reason TEXT,
  FOREIGN KEY(application_id) REFERENCES ip_application(id) ON DELETE RESTRICT,
  FOREIGN KEY(ip_pool_id) REFERENCES ip_address_pool(id) ON DELETE RESTRICT,
  FOREIGN KEY(assigned_by) REFERENCES sys_user(id) ON DELETE RESTRICT,
  CHECK((assignment_source='APPLICATION' AND application_id IS NOT NULL AND manual_registered_by IS NULL) OR
        (assignment_source='MANUAL_REGISTRATION' AND application_id IS NULL AND manual_asset_id IS NOT NULL AND manual_registered_by IS NOT NULL AND manual_registered_at IS NOT NULL AND source_pool_state IS NOT NULL))
);
INSERT INTO ip_assignment_v020(id,application_id,ip_pool_id,resource_code,assigned_by,assigned_at,status,released_by,released_at,release_reason,version,switch_port,assignment_source)
  SELECT id,application_id,ip_pool_id,resource_code,assigned_by,assigned_at,status,released_by,released_at,release_reason,version,switch_port,'APPLICATION'
  FROM ip_assignment;
DROP TABLE ip_assignment;
ALTER TABLE ip_assignment_v020 RENAME TO ip_assignment;
CREATE INDEX idx_ip_assignment_status_time ON ip_assignment(status,assigned_at,released_at);
CREATE INDEX idx_ip_assignment_responsible ON ip_assignment(responsible_tech_user_id,status);
CREATE UNIQUE INDEX uk_ip_assignment_active_pool ON ip_assignment(ip_pool_id) WHERE status='ASSIGNED';
CREATE UNIQUE INDEX uk_ip_assignment_active_resource_code ON ip_assignment(resource_code) WHERE status='ASSIGNED' AND resource_code IS NOT NULL;

CREATE TABLE ip_release_event_v020 (
  id INTEGER PRIMARY KEY,
  assignment_id INTEGER NOT NULL,
  application_id INTEGER,
  ip_address TEXT NOT NULL,
  resource_code TEXT,
  switch_port TEXT CHECK(switch_port IS NULL OR length(switch_port) <= 64),
  released_by INTEGER NOT NULL,
  released_by_username TEXT NOT NULL,
  released_at TEXT NOT NULL,
  branch_name TEXT NOT NULL,
  outlet_name TEXT NOT NULL,
  asset_type TEXT NOT NULL,
  result TEXT NOT NULL CHECK(result IN ('RELEASED')),
  request_id TEXT NOT NULL UNIQUE,
  assignment_source TEXT NOT NULL DEFAULT 'APPLICATION' CHECK(assignment_source IN ('APPLICATION','MANUAL_REGISTRATION')),
  registered_by INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT,
  registered_by_username TEXT,
  registered_at TEXT,
  responsible_tech_user_id INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT,
  responsible_tech_username TEXT,
  manual_note TEXT,
  source_pool_state TEXT,
  FOREIGN KEY(assignment_id) REFERENCES ip_assignment(id) ON DELETE RESTRICT,
  FOREIGN KEY(application_id) REFERENCES ip_application(id) ON DELETE RESTRICT,
  FOREIGN KEY(released_by) REFERENCES sys_user(id) ON DELETE RESTRICT
);
INSERT INTO ip_release_event_v020(id,assignment_id,application_id,ip_address,resource_code,switch_port,released_by,released_by_username,released_at,branch_name,outlet_name,asset_type,result,request_id,assignment_source)
  SELECT id,assignment_id,application_id,ip_address,resource_code,switch_port,released_by,released_by_username,released_at,branch_name,outlet_name,asset_type,result,request_id,'APPLICATION'
  FROM ip_release_event;
DROP TABLE ip_release_event;
ALTER TABLE ip_release_event_v020 RENAME TO ip_release_event;
CREATE UNIQUE INDEX uk_ip_release_event_assignment ON ip_release_event(assignment_id);
CREATE INDEX idx_ip_release_event_time ON ip_release_event(released_at);
CREATE INDEX idx_ip_release_event_releaser ON ip_release_event(released_by,released_at);

CREATE TABLE user_notification_v020 (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL,
  application_id INTEGER,
  message TEXT NOT NULL CHECK(length(message) BETWEEN 1 AND 500),
  is_read INTEGER NOT NULL DEFAULT 0 CHECK(is_read IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  release_event_id INTEGER REFERENCES ip_release_event(id) ON DELETE RESTRICT,
  FOREIGN KEY(user_id) REFERENCES sys_user(id) ON DELETE RESTRICT,
  FOREIGN KEY(application_id) REFERENCES ip_application(id) ON DELETE RESTRICT
);
INSERT INTO user_notification_v020(id,user_id,application_id,message,is_read,created_at,release_event_id)
  SELECT id,user_id,application_id,message,is_read,created_at,release_event_id
  FROM user_notification;
DROP TABLE user_notification;
ALTER TABLE user_notification_v020 RENAME TO user_notification;
CREATE INDEX idx_user_notification_user_time ON user_notification(user_id,created_at);
CREATE UNIQUE INDEX uk_user_notification_release_recipient ON user_notification(user_id,release_event_id) WHERE release_event_id IS NOT NULL;
CREATE INDEX idx_user_notification_release_event ON user_notification(release_event_id);
