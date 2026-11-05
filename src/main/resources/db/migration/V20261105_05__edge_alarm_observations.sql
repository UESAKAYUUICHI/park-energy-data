CREATE TABLE data_edge_alarm_observation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    gateway_id BIGINT UNSIGNED NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    device_id BIGINT UNSIGNED NOT NULL,
    event_action VARCHAR(20) NOT NULL,
    event_time DATETIME(3) NOT NULL,
    payload_json JSON NOT NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_edge_observation_message (gateway_id,message_id),
    KEY idx_edge_observation_device_time (device_id,event_time)
) ENGINE=InnoDB;
