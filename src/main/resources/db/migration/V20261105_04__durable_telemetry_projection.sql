CREATE TABLE IF NOT EXISTS data_sample_receipt (
    device_id BIGINT NOT NULL,
    collect_time DATETIME(3) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id,collect_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS data_projection_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    device_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_projection_pending (status,next_attempt_at,id),
    KEY idx_projection_device (device_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
