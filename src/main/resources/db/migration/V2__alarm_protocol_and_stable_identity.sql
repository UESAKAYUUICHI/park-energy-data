CREATE TABLE IF NOT EXISTS alarm_protocol (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protocol_key VARCHAR(64) NOT NULL,
  protocol_name VARCHAR(100) NOT NULL,
  version_no INT NOT NULL DEFAULT 1,
  lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_protocol_key (protocol_key),
  KEY idx_alarm_protocol_status (lifecycle_status, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警协议';

CREATE TABLE IF NOT EXISTS alarm_protocol_point (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protocol_id BIGINT UNSIGNED NOT NULL,
  point_code VARCHAR(64) NOT NULL,
  point_name VARCHAR(100) NOT NULL,
  alarm_type TINYINT NOT NULL DEFAULT 5,
  evaluation_mode VARCHAR(24) NOT NULL DEFAULT 'THRESHOLD',
  compare_operator VARCHAR(10) NOT NULL DEFAULT '>',
  threshold_value DECIMAL(12,4) NULL,
  threshold_min DECIMAL(12,4) NULL,
  threshold_max DECIMAL(12,4) NULL,
  recovery_threshold_value DECIMAL(12,4) NULL,
  recovery_samples INT NOT NULL DEFAULT 3,
  freshness_seconds INT NOT NULL DEFAULT 900,
  max_sample_gap_seconds INT NOT NULL DEFAULT 900,
  evaluation_window_samples INT NOT NULL DEFAULT 5,
  required_hits INT NOT NULL DEFAULT 3,
  window_seconds INT NOT NULL DEFAULT 300,
  duration_seconds INT NOT NULL DEFAULT 0,
  alarm_level TINYINT NOT NULL DEFAULT 2,
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_protocol_point (protocol_id, point_code),
  KEY idx_alarm_protocol_point_code (point_code, enabled),
  CONSTRAINT fk_alarm_protocol_point_protocol FOREIGN KEY (protocol_id)
    REFERENCES alarm_protocol(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警协议监测测点';

CREATE TABLE IF NOT EXISTS alarm_protocol_device (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protocol_id BIGINT UNSIGNED NOT NULL,
  device_id BIGINT UNSIGNED NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_protocol_device (protocol_id, device_id),
  KEY idx_alarm_protocol_device_device (device_id, enabled),
  CONSTRAINT fk_alarm_protocol_device_protocol FOREIGN KEY (protocol_id)
    REFERENCES alarm_protocol(id) ON DELETE CASCADE,
  CONSTRAINT fk_alarm_protocol_device_device FOREIGN KEY (device_id)
    REFERENCES dev_device(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警协议设备绑定';

ALTER TABLE log_alarm
  ADD COLUMN protocol_id BIGINT UNSIGNED NULL AFTER rule_version_id,
  ADD COLUMN protocol_point_id BIGINT UNSIGNED NULL AFTER protocol_id,
  ADD COLUMN alarm_key VARCHAR(180) NULL AFTER event_fingerprint;

UPDATE log_alarm a
JOIN (
  SELECT event_fingerprint
  FROM log_alarm
  WHERE event_fingerprint IS NOT NULL
  GROUP BY event_fingerprint
  HAVING COUNT(*) = 1
) stable ON stable.event_fingerprint = a.event_fingerprint
SET a.alarm_key = a.event_fingerprint
WHERE a.alarm_key IS NULL;

CREATE UNIQUE INDEX uk_log_alarm_alarm_key ON log_alarm(alarm_key);
CREATE INDEX idx_log_alarm_protocol_point ON log_alarm(protocol_id, protocol_point_id, device_id);
