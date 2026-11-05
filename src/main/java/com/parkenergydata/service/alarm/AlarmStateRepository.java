package com.parkenergydata.service.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class AlarmStateRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AlarmStateRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public AlarmEvaluationState lock(long deviceId,long ruleId,String namespace) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Alarm state requires a transaction");
        jdbc.update("""
                INSERT INTO data_alarm_evaluation_state(device_id,rule_id,rule_namespace,state_json)
                VALUES (?,?,?,'{}') ON DUPLICATE KEY UPDATE device_id=device_id
                """,deviceId,ruleId,namespace);
        String value=jdbc.queryForObject("""
                SELECT state_json FROM data_alarm_evaluation_state
                WHERE device_id=? AND rule_id=? AND rule_namespace=? FOR UPDATE
                """,String.class,deviceId,ruleId,namespace);
        try { return json.readValue(value,AlarmEvaluationState.class); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid alarm evaluation state",e); }
    }
    public void save(long deviceId,long ruleId,String namespace,AlarmEvaluationState state) {
        try {
            jdbc.update("""
                    UPDATE data_alarm_evaluation_state SET state_json=?,updated_at=CURRENT_TIMESTAMP(3)
                    WHERE device_id=? AND rule_id=? AND rule_namespace=?
                    """,json.writeValueAsString(state),deviceId,ruleId,namespace);
        } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize alarm state",e); }
    }
}
