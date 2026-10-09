ALTER TABLE ip_assignment ADD COLUMN switch_ip TEXT CHECK(switch_ip IS NULL OR length(switch_ip) <= 15);
ALTER TABLE ip_assignment ADD COLUMN serial_number TEXT CHECK(serial_number IS NULL OR length(serial_number) <= 100);
ALTER TABLE ip_release_event ADD COLUMN switch_ip TEXT CHECK(switch_ip IS NULL OR length(switch_ip) <= 15);
ALTER TABLE ip_release_event ADD COLUMN serial_number TEXT CHECK(serial_number IS NULL OR length(serial_number) <= 100);
ALTER TABLE ip_address_pool ADD COLUMN is_occupied INTEGER NOT NULL DEFAULT 0 CHECK(is_occupied IN (0,1));

WITH raw AS (
  SELECT id,substr(switch_port,1,instr(switch_port,'-')-1) AS host,substr(switch_port,instr(switch_port,'-')+1) AS port
  FROM ip_assignment WHERE instr(switch_port,'-') > 0
), first AS (
  SELECT *,substr(host,1,instr(host,'.')-1) AS a,substr(host,instr(host,'.')+1) AS rest FROM raw
), second AS (
  SELECT *,substr(rest,1,instr(rest,'.')-1) AS b,substr(rest,instr(rest,'.')+1) AS rest2 FROM first
), octets AS (
  SELECT *,substr(rest2,1,instr(rest2,'.')-1) AS c,substr(rest2,instr(rest2,'.')+1) AS d FROM second
), valid AS (
  SELECT * FROM octets WHERE host NOT GLOB '*[^0-9.]*' AND length(host)-length(replace(host,'.',''))=3
  AND length(a) BETWEEN 1 AND 3 AND length(b) BETWEEN 1 AND 3 AND length(c) BETWEEN 1 AND 3 AND length(d) BETWEEN 1 AND 3
  AND CAST(a AS INTEGER) BETWEEN 0 AND 255 AND CAST(b AS INTEGER) BETWEEN 0 AND 255
  AND CAST(c AS INTEGER) BETWEEN 0 AND 255 AND CAST(d AS INTEGER) BETWEEN 0 AND 255 AND length(port)>0
)
UPDATE ip_assignment SET switch_ip=(SELECT host FROM valid WHERE valid.id=ip_assignment.id),switch_port=(SELECT port FROM valid WHERE valid.id=ip_assignment.id) WHERE id IN (SELECT id FROM valid);

WITH raw AS (
  SELECT id,substr(switch_port,1,instr(switch_port,'-')-1) AS host,substr(switch_port,instr(switch_port,'-')+1) AS port
  FROM ip_release_event WHERE instr(switch_port,'-') > 0
), first AS (
  SELECT *,substr(host,1,instr(host,'.')-1) AS a,substr(host,instr(host,'.')+1) AS rest FROM raw
), second AS (
  SELECT *,substr(rest,1,instr(rest,'.')-1) AS b,substr(rest,instr(rest,'.')+1) AS rest2 FROM first
), octets AS (
  SELECT *,substr(rest2,1,instr(rest2,'.')-1) AS c,substr(rest2,instr(rest2,'.')+1) AS d FROM second
), valid AS (
  SELECT * FROM octets WHERE host NOT GLOB '*[^0-9.]*' AND length(host)-length(replace(host,'.',''))=3
  AND length(a) BETWEEN 1 AND 3 AND length(b) BETWEEN 1 AND 3 AND length(c) BETWEEN 1 AND 3 AND length(d) BETWEEN 1 AND 3
  AND CAST(a AS INTEGER) BETWEEN 0 AND 255 AND CAST(b AS INTEGER) BETWEEN 0 AND 255
  AND CAST(c AS INTEGER) BETWEEN 0 AND 255 AND CAST(d AS INTEGER) BETWEEN 0 AND 255 AND length(port)>0
)
UPDATE ip_release_event SET switch_ip=(SELECT host FROM valid WHERE valid.id=ip_release_event.id),switch_port=(SELECT port FROM valid WHERE valid.id=ip_release_event.id) WHERE id IN (SELECT id FROM valid);
