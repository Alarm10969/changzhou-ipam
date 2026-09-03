ALTER TABLE branch ADD COLUMN deleted_at TEXT;
ALTER TABLE outlet ADD COLUMN deleted_at TEXT;
ALTER TABLE ip_range ADD COLUMN deleted_at TEXT;
ALTER TABLE ip_address_pool ADD COLUMN deleted_at TEXT;

CREATE INDEX idx_branch_visible ON branch(deleted_at,is_active,name);
CREATE INDEX idx_outlet_visible ON outlet(deleted_at,is_active,branch_id,name);
CREATE INDEX idx_ip_range_visible ON ip_range(deleted_at,is_active,outlet_id,cidr);
CREATE INDEX idx_ip_pool_visible ON ip_address_pool(deleted_at,ip_range_id,is_active,is_reserved,ip_address);

INSERT OR IGNORE INTO system_config(config_key,config_value,description)
VALUES('default_dns_primary','','新建或导入地址段未填写主 DNS 时使用');
INSERT OR IGNORE INTO system_config(config_key,config_value,description)
VALUES('default_dns_secondary','','新建或导入地址段未填写备 DNS 时使用');
