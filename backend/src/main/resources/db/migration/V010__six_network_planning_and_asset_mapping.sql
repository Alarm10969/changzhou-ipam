-- Stage three baseline: one active, versioned address plan and six network segments per outlet.
-- Existing single-range records remain historical/read-only; they are deliberately not guessed or converted.
DROP INDEX IF EXISTS uk_ip_range_outlet;
ALTER TABLE ip_range ADD COLUMN range_type TEXT;
ALTER TABLE ip_range ADD COLUMN planning_group TEXT;
ALTER TABLE ip_range ADD COLUMN rule_version_id INTEGER;
CREATE UNIQUE INDEX uk_ip_range_outlet_type_active
  ON ip_range(outlet_id,range_type) WHERE outlet_id IS NOT NULL AND deleted_at IS NULL AND range_type IS NOT NULL;
CREATE INDEX idx_ip_range_planning_group ON ip_range(outlet_id,planning_group,range_type);

CREATE TABLE planning_rule_version (
  id INTEGER PRIMARY KEY,
  version_no INTEGER NOT NULL UNIQUE,
  global_cidr TEXT NOT NULL,
  outlet_block_prefix INTEGER NOT NULL CHECK(outlet_block_prefix BETWEEN 8 AND 30),
  segment_prefix INTEGER NOT NULL CHECK(segment_prefix BETWEEN 8 AND 30),
  gateway_host_offset INTEGER NOT NULL CHECK(gateway_host_offset BETWEEN 1 AND 254),
  dns_primary TEXT NOT NULL,
  dns_secondary TEXT,
  is_active INTEGER NOT NULL DEFAULT 0 CHECK(is_active IN (0,1)),
  change_reason TEXT NOT NULL CHECK(length(change_reason) BETWEEN 1 AND 500),
  activated_by INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT,
  activated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE UNIQUE INDEX uk_planning_rule_active ON planning_rule_version(is_active) WHERE is_active=1;

CREATE TABLE network_segment_type (
  id INTEGER PRIMARY KEY,
  rule_version_id INTEGER NOT NULL,
  code TEXT NOT NULL CHECK(code IN ('PRODUCTION','NETWORK_MANAGEMENT','EXTENSION','MONITORING','TEST','OFFICE')),
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 32),
  relative_offset INTEGER NOT NULL CHECK(relative_offset BETWEEN 0 AND 255),
  is_enabled INTEGER NOT NULL DEFAULT 1 CHECK(is_enabled IN (0,1)),
  retired_at TEXT,
  FOREIGN KEY(rule_version_id) REFERENCES planning_rule_version(id) ON DELETE RESTRICT,
  UNIQUE(rule_version_id,code),
  UNIQUE(rule_version_id,relative_offset)
);

CREATE TABLE asset_category (
  id INTEGER PRIMARY KEY,
  asset_type TEXT NOT NULL CHECK(asset_type IN ('TECH','NON_TECH')),
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  retired_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE(asset_type,name)
);
CREATE TABLE asset_subcategory (
  id INTEGER PRIMARY KEY,
  category_id INTEGER NOT NULL,
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  retired_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  FOREIGN KEY(category_id) REFERENCES asset_category(id) ON DELETE RESTRICT,
  UNIQUE(category_id,name)
);
CREATE TABLE asset_network_mapping (
  id INTEGER PRIMARY KEY,
  rule_version_id INTEGER NOT NULL,
  subcategory_id INTEGER NOT NULL,
  range_type TEXT NOT NULL CHECK(range_type IN ('PRODUCTION','NETWORK_MANAGEMENT','EXTENSION','MONITORING','TEST','OFFICE')),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
  change_reason TEXT NOT NULL CHECK(length(change_reason) BETWEEN 1 AND 500),
  changed_by INTEGER REFERENCES sys_user(id) ON DELETE RESTRICT,
  changed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  retired_at TEXT,
  FOREIGN KEY(rule_version_id) REFERENCES planning_rule_version(id) ON DELETE RESTRICT,
  FOREIGN KEY(subcategory_id) REFERENCES asset_subcategory(id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uk_asset_network_mapping_active ON asset_network_mapping(rule_version_id,subcategory_id) WHERE is_active=1;

INSERT INTO planning_rule_version(version_no,global_cidr,outlet_block_prefix,segment_prefix,gateway_host_offset,dns_primary,dns_secondary,is_active,change_reason)
  VALUES(1,'108.56.0.0/13',20,24,1,'8.8.8.8',NULL,1,'第三阶段基线初始化');
INSERT INTO network_segment_type(rule_version_id,code,name,relative_offset,is_enabled)
  SELECT id,'PRODUCTION','生产',0,1 FROM planning_rule_version WHERE version_no=1;
INSERT INTO network_segment_type(rule_version_id,code,name,relative_offset,is_enabled)
  SELECT id,'NETWORK_MANAGEMENT','网络管理',3,1 FROM planning_rule_version WHERE version_no=1;
INSERT INTO network_segment_type(rule_version_id,code,name,relative_offset,is_enabled)
  SELECT id,'EXTENSION','扩展',11,1 FROM planning_rule_version WHERE version_no=1;
INSERT INTO network_segment_type(rule_version_id,code,name,relative_offset,is_enabled)
  SELECT id,'MONITORING','监控',12,1 FROM planning_rule_version WHERE version_no=1;
INSERT INTO network_segment_type(rule_version_id,code,name,relative_offset,is_enabled)
  SELECT id,'TEST','测试',13,1 FROM planning_rule_version WHERE version_no=1;
INSERT INTO network_segment_type(rule_version_id,code,name,relative_offset,is_enabled)
  SELECT id,'OFFICE','办公',15,1 FROM planning_rule_version WHERE version_no=1;

INSERT INTO asset_category(asset_type,name) VALUES('TECH','科技资产');
INSERT INTO asset_category(asset_type,name) VALUES('NON_TECH','安防保卫设备');
INSERT INTO asset_category(asset_type,name) VALUES('NON_TECH','渠道运营设备');
INSERT INTO asset_category(asset_type,name) VALUES('NON_TECH','行政办公设备');
INSERT INTO asset_category(asset_type,name) VALUES('NON_TECH','其他');
INSERT INTO asset_subcategory(category_id,name) SELECT id,'VOIP对讲' FROM asset_category WHERE name='安防保卫设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'视频监控' FROM asset_category WHERE name='安防保卫设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'报警主机' FROM asset_category WHERE name='安防保卫设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'网络摄像机' FROM asset_category WHERE name='安防保卫设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'门禁系统' FROM asset_category WHERE name='安防保卫设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'联动门' FROM asset_category WHERE name='安防保卫设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'其他' FROM asset_category WHERE name='安防保卫设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'清分机' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'本外币一体点验钞机' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'点钞机' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'智慧银行设备' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'智能机器人' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'智能迎宾机器人' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'基金托管库系统' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'智能卡库' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'智能展示窗' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'联网信息发布终端' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'电子广告屏' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'电子门楣等' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'其他' FROM asset_category WHERE name='渠道运营设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'打印复用传真一体机' FROM asset_category WHERE name='行政办公设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'考勤机' FROM asset_category WHERE name='行政办公设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'后勤打卡机' FROM asset_category WHERE name='行政办公设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'其他' FROM asset_category WHERE name='行政办公设备';
INSERT INTO asset_subcategory(category_id,name) SELECT id,'其他' FROM asset_category WHERE name='其他';

-- Do not rebuild ip_application here: historical assignments refer to it and must survive upgrade.
-- The new service enforces the conditional PC/resource-code rules for all newly submitted rows.
ALTER TABLE ip_application ADD COLUMN applicant_username TEXT;
ALTER TABLE ip_application ADD COLUMN category_id INTEGER REFERENCES asset_category(id) ON DELETE RESTRICT;
ALTER TABLE ip_application ADD COLUMN subcategory_id INTEGER REFERENCES asset_subcategory(id) ON DELETE RESTRICT;
ALTER TABLE ip_application ADD COLUMN user_name TEXT;
ALTER TABLE ip_application ADD COLUMN user_unified_auth_id TEXT;
ALTER TABLE ip_application ADD COLUMN recommended_range_type TEXT;
ALTER TABLE ip_application ADD COLUMN mapping_id INTEGER REFERENCES asset_network_mapping(id) ON DELETE RESTRICT;
ALTER TABLE ip_application ADD COLUMN rule_version_id INTEGER REFERENCES planning_rule_version(id) ON DELETE RESTRICT;
ALTER TABLE ip_application ADD COLUMN final_range_type TEXT;
ALTER TABLE ip_application ADD COLUMN override_reason TEXT;
ALTER TABLE ip_application ADD COLUMN approval_opinion TEXT;
CREATE INDEX idx_ip_application_mapping ON ip_application(subcategory_id,recommended_range_type,final_range_type);
