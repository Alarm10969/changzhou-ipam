ALTER TABLE ip_assignment ADD COLUMN switch_port TEXT CHECK(switch_port IS NULL OR length(switch_port) <= 64);
ALTER TABLE ip_release_event ADD COLUMN switch_port TEXT CHECK(switch_port IS NULL OR length(switch_port) <= 64);
