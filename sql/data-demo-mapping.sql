USE `park_energy_system`;

INSERT INTO `dev_point_definition`
  (`id`, `device_type_id`, `point_code`, `point_name`, `data_type`, `unit`, `precision_scale`, `business_role`, `billable`, `stat_enabled`, `sort`, `enabled`)
VALUES
  (1, 1, 'total_active_energy', '正向有功总电能', 'DOUBLE', 'kWh', 2, 'TOTAL_ACCUMULATED', 1, 1, 1, 1),
  (2, 1, 'voltage_a', 'A相电压', 'DOUBLE', 'V', 1, 'INSTANT_VALUE', 0, 1, 2, 1),
  (3, 1, 'current_a', 'A相电流', 'DOUBLE', 'A', 2, 'INSTANT_VALUE', 0, 1, 3, 1)
ON DUPLICATE KEY UPDATE
  `point_name` = VALUES(`point_name`),
  `data_type` = VALUES(`data_type`),
  `unit` = VALUES(`unit`),
  `precision_scale` = VALUES(`precision_scale`),
  `business_role` = VALUES(`business_role`),
  `billable` = VALUES(`billable`),
  `stat_enabled` = VALUES(`stat_enabled`),
  `sort` = VALUES(`sort`),
  `enabled` = VALUES(`enabled`);

INSERT INTO `dev_point_mapping`
  (`id`, `device_type_id`, `point_code`, `protocol_type`, `source_path`, `function_code`, `register_address`, `register_length`, `value_type`, `byte_order`, `scale_factor`, `offset_value`, `expression`, `required`)
VALUES
  (1, 1, 'total_active_energy', 'JSON', '$.registers.totalActiveEnergy', NULL, NULL, NULL, 'DOUBLE', NULL, 0.010000, 0.000000, NULL, 1),
  (2, 1, 'voltage_a', 'JSON', '$.registers.voltageA', NULL, NULL, NULL, 'DOUBLE', NULL, 0.100000, 0.000000, NULL, 1),
  (3, 1, 'current_a', 'JSON', '$.registers.currentA', NULL, NULL, NULL, 'DOUBLE', NULL, 0.010000, 0.000000, NULL, 0)
ON DUPLICATE KEY UPDATE
  `protocol_type` = VALUES(`protocol_type`),
  `source_path` = VALUES(`source_path`),
  `function_code` = VALUES(`function_code`),
  `register_address` = VALUES(`register_address`),
  `register_length` = VALUES(`register_length`),
  `value_type` = VALUES(`value_type`),
  `byte_order` = VALUES(`byte_order`),
  `scale_factor` = VALUES(`scale_factor`),
  `offset_value` = VALUES(`offset_value`),
  `expression` = VALUES(`expression`),
  `required` = VALUES(`required`);

INSERT INTO `alarm_rule`
  (`id`, `rule_name`, `alarm_type`, `rule_scope`, `org_id`, `device_id`, `point_code`, `compare_operator`, `threshold_value`, `threshold_min`, `threshold_max`, `duration_seconds`, `alarm_level`, `enabled`, `remark`)
VALUES
  (1, 'A相电压过高', 1, 1, NULL, NULL, 'voltage_a', '>', 250.0000, NULL, NULL, 60, 2, 1, '演示规则')
ON DUPLICATE KEY UPDATE
  `rule_name` = VALUES(`rule_name`),
  `alarm_type` = VALUES(`alarm_type`),
  `rule_scope` = VALUES(`rule_scope`),
  `org_id` = VALUES(`org_id`),
  `device_id` = VALUES(`device_id`),
  `point_code` = VALUES(`point_code`),
  `compare_operator` = VALUES(`compare_operator`),
  `threshold_value` = VALUES(`threshold_value`),
  `threshold_min` = VALUES(`threshold_min`),
  `threshold_max` = VALUES(`threshold_max`),
  `duration_seconds` = VALUES(`duration_seconds`),
  `alarm_level` = VALUES(`alarm_level`),
  `enabled` = VALUES(`enabled`),
  `remark` = VALUES(`remark`);
