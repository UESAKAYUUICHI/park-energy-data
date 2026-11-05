ALTER TABLE data_alarm_evaluation_state
  ADD COLUMN rule_namespace VARCHAR(16) NOT NULL DEFAULT 'RULE',
  DROP PRIMARY KEY,
  ADD PRIMARY KEY(device_id,rule_namespace,rule_id);
UPDATE data_alarm_evaluation_state
SET rule_namespace='PROTOCOL'
WHERE JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.ruleVersion')) LIKE '%protocolId=%'
AND JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.ruleVersion')) NOT LIKE '%protocolId=null%';
