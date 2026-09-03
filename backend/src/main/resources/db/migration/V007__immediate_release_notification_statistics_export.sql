-- Stage three: immediate self-service release.  Existing V006 assignments remain active.
ALTER TABLE ip_assignment ADD COLUMN status TEXT NOT NULL DEFAULT 'ASSIGNED'
  CHECK(status IN ('ASSIGNED','RELEASED'));
ALTER TABLE ip_assignment ADD COLUMN released_by INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT;
ALTER TABLE ip_assignment ADD COLUMN released_at TEXT;
ALTER TABLE ip_assignment ADD COLUMN release_reason TEXT CHECK(release_reason IS NULL OR length(release_reason) <= 500);
ALTER TABLE ip_assignment ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE ip_release_event (
  id INTEGER PRIMARY KEY,
  assignment_id INTEGER NOT NULL,
  application_id INTEGER NOT NULL,
  ip_address TEXT NOT NULL,
  resource_code TEXT,
  released_by INTEGER NOT NULL,
  released_by_username TEXT NOT NULL,
  released_at TEXT NOT NULL,
  branch_name TEXT NOT NULL,
  outlet_name TEXT NOT NULL,
  asset_type TEXT NOT NULL,
  result TEXT NOT NULL CHECK(result IN ('RELEASED')),
  request_id TEXT NOT NULL UNIQUE,
  FOREIGN KEY(assignment_id) REFERENCES ip_assignment(id) ON DELETE RESTRICT,
  FOREIGN KEY(application_id) REFERENCES ip_application(id) ON DELETE RESTRICT,
  FOREIGN KEY(released_by) REFERENCES sys_user(id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uk_ip_release_event_assignment ON ip_release_event(assignment_id);
CREATE INDEX idx_ip_release_event_time ON ip_release_event(released_at);
CREATE INDEX idx_ip_release_event_releaser ON ip_release_event(released_by,released_at);

ALTER TABLE user_notification ADD COLUMN release_event_id INTEGER REFERENCES ip_release_event(id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uk_user_notification_release_recipient
  ON user_notification(user_id,release_event_id) WHERE release_event_id IS NOT NULL;
CREATE INDEX idx_user_notification_release_event ON user_notification(release_event_id);

DROP INDEX uk_ip_assignment_pool;
DROP INDEX uk_ip_assignment_resource_code;
CREATE UNIQUE INDEX uk_ip_assignment_active_pool ON ip_assignment(ip_pool_id) WHERE status='ASSIGNED';
CREATE UNIQUE INDEX uk_ip_assignment_active_resource_code ON ip_assignment(resource_code)
  WHERE status='ASSIGNED' AND resource_code IS NOT NULL;
CREATE INDEX idx_ip_assignment_status_time ON ip_assignment(status,assigned_at,released_at);
