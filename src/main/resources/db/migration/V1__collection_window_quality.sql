CREATE TABLE IF NOT EXISTS stats_collection_window (
  device_id BIGINT NOT NULL,
  window_start DATETIME NOT NULL,
  window_end DATETIME NOT NULL,
  expected_samples INT NOT NULL,
  received_samples INT NOT NULL DEFAULT 0,
  data_complete_rate DECIMAL(5,2) NOT NULL DEFAULT 0,
  first_collect_time DATETIME NULL,
  last_collect_time DATETIME NULL,
  quality_status VARCHAR(24) NOT NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (device_id, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stats_collection_window_sample (
  device_id BIGINT NOT NULL,
  collect_time DATETIME NOT NULL,
  window_start DATETIME NOT NULL,
  sample_interval_seconds INT NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (device_id, collect_time),
  KEY idx_collection_window_sample_window (device_id, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
