CREATE TABLE IF NOT EXISTS data_alarm_evaluation_state (
    device_id BIGINT NOT NULL,
    rule_id BIGINT NOT NULL,
    state_json JSON NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id,rule_id),
    KEY idx_alarm_state_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
