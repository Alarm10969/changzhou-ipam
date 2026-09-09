-- V004 required every technology application to carry a resource code.  The
-- confirmed rule is different for PCs: the employee name and unified ID are the
-- required identity, while non-PC technology assets require a resource code.
-- Rebuild retains every post-V012 column and all existing primary-key values.
CREATE TABLE ip_application_v013 (
  id INTEGER PRIMARY KEY,
  applicant_user_id INTEGER NOT NULL,
  applicant_name TEXT NOT NULL CHECK(length(applicant_name) BETWEEN 1 AND 100),
  unified_auth_id TEXT NOT NULL CHECK(length(unified_auth_id) BETWEEN 1 AND 64),
  branch_id INTEGER NOT NULL,
  outlet_id INTEGER NOT NULL,
  asset_type TEXT NOT NULL CHECK(asset_type IN ('TECH','NON_TECH')),
  asset_code TEXT,
  device_type TEXT CHECK(device_type IN ('PC','OTHER')),
  status TEXT NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED')),
  rejection_reason TEXT,
  reviewed_by INTEGER,
  reviewed_at TEXT,
  version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  applicant_username TEXT,
  category_id INTEGER,
  subcategory_id INTEGER,
  user_name TEXT,
  user_unified_auth_id TEXT,
  recommended_range_type TEXT,
  mapping_id INTEGER,
  rule_version_id INTEGER,
  final_range_type TEXT,
  approval_opinion TEXT,
  FOREIGN KEY(applicant_user_id) REFERENCES sys_user(id) ON DELETE RESTRICT,
  FOREIGN KEY(branch_id) REFERENCES branch(id) ON DELETE RESTRICT,
  FOREIGN KEY(outlet_id) REFERENCES outlet(id) ON DELETE RESTRICT,
  FOREIGN KEY(reviewed_by) REFERENCES sys_user(id) ON DELETE RESTRICT,
  FOREIGN KEY(category_id) REFERENCES asset_category(id) ON DELETE RESTRICT,
  FOREIGN KEY(subcategory_id) REFERENCES asset_subcategory(id) ON DELETE RESTRICT,
  FOREIGN KEY(mapping_id) REFERENCES asset_network_mapping(id) ON DELETE RESTRICT,
  FOREIGN KEY(rule_version_id) REFERENCES planning_rule_version(id) ON DELETE RESTRICT,
  CHECK(
    (asset_type='TECH' AND (
      (device_type='PC' AND asset_code IS NULL AND user_name IS NOT NULL AND user_unified_auth_id IS NOT NULL) OR
      (device_type='PC' AND asset_code IS NOT NULL AND user_name IS NULL AND user_unified_auth_id IS NULL) OR
      (device_type='OTHER' AND asset_code IS NOT NULL) OR
      (device_type IS NULL AND asset_code IS NOT NULL)
    )) OR
    (asset_type='NON_TECH' AND asset_code IS NULL AND device_type IS NULL AND user_name IS NULL AND user_unified_auth_id IS NULL)
  ),
  CHECK((status='REJECTED' AND rejection_reason IS NOT NULL) OR status<>'REJECTED')
);
INSERT INTO ip_application_v013(id,applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status,rejection_reason,reviewed_by,reviewed_at,version,created_at,updated_at,applicant_username,category_id,subcategory_id,user_name,user_unified_auth_id,recommended_range_type,mapping_id,rule_version_id,final_range_type,approval_opinion)
SELECT id,applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status,rejection_reason,reviewed_by,reviewed_at,version,created_at,updated_at,applicant_username,category_id,subcategory_id,user_name,user_unified_auth_id,recommended_range_type,mapping_id,rule_version_id,final_range_type,approval_opinion
FROM ip_application;
DROP TABLE ip_application;
ALTER TABLE ip_application_v013 RENAME TO ip_application;
CREATE INDEX idx_ip_application_owner_time ON ip_application(applicant_user_id,created_at);
CREATE INDEX idx_ip_application_pending ON ip_application(status,branch_id,created_at) WHERE status='PENDING';
CREATE INDEX idx_ip_application_mapping ON ip_application(subcategory_id,recommended_range_type,final_range_type);
