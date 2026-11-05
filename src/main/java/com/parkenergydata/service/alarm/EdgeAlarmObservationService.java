package com.parkenergydata.service.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayAlarmPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EdgeAlarmObservationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EdgeAlarmObservationService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Published measurement rules are evaluated from accepted samples in the cloud.
     * Edge notifications remain evidence, so one anomaly cannot create two incidents. */
    public boolean recordProtocolObservation(AccessForwardMessage forward, GatewayAlarmPayload payload, Long deviceId) {
        if (!payload.eventId().startsWith("ALARM_PROTOCOL:")) return false;
        if (deviceId == null) throw new BusinessException("Protocol observation requires a registered device");
        try {
            jdbc.update("""
                INSERT IGNORE INTO data_edge_alarm_observation
                  (gateway_id,message_id,event_id,device_id,event_action,event_time,payload_json)
                VALUES (?,?,?,?,?,FROM_UNIXTIME(?/1000.0),?)
                """, forward.gatewayId(), payload.messageId(), payload.eventId(), deviceId,
                    payload.action(), payload.timestamp(), json.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Invalid alarm observation");
        }
        return true;
    }
}
