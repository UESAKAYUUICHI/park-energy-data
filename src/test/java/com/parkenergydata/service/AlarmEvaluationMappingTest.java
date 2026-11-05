package com.parkenergydata.service;

import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.AlarmRule;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.repository.AlarmRuleRepository;
import com.parkenergydata.service.alarm.AlarmEvaluationState;
import com.parkenergydata.service.alarm.AlarmStateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AlarmEvaluationMappingTest {
    @Test void publishedLowercasePointMatchesCanonicalUppercaseMeasurement() {
        AlarmRuleRepository rules = mock(AlarmRuleRepository.class);
        AlarmStateRepository states = mock(AlarmStateRepository.class);
        AlarmRule rule = new AlarmRule(1L, "Voltage", 1, 3, 1L, 7L,
                "voltage_a", ">", new BigDecimal("245"), null, null, 0, 2, true);
        DevDevice device = new DevDevice(7L,"meter","meter",3L,1L,1L,"1",1,false,"MONITOR",5,BigDecimal.valueOf(80));
        when(rules.findEnabledForDevice(7L,1L)).thenReturn(List.of(rule));
        when(states.lock(7L,1L,"RULE")).thenReturn(new AlarmEvaluationState());
        Instant now = Instant.now();
        new AlarmEvaluateService(rules,states).evaluate(device,List.of(
                new ParsedPoint("VOLTAGE_A","DECIMAL","VOLTAGE",true,280,BigDecimal.valueOf(280))),now);
        verify(rules).upsertAlarm(eq(rule),eq(7L),eq(1L),eq("280"),eq("245"),eq(now));
        verify(states).save(eq(7L),eq(1L),eq("RULE"),any());
    }

    @Test void unsupportedModeCannotFallThroughToThresholdEvaluation() {
        AlarmRuleRepository rules = mock(AlarmRuleRepository.class);
        AlarmStateRepository states = mock(AlarmStateRepository.class);
        AlarmRule rule = mock(AlarmRule.class);
        when(rule.evaluationMode()).thenReturn("SCENE_IFOREST");
        when(rules.findEnabledForDevice(7L,1L)).thenReturn(List.of(rule));
        DevDevice device = new DevDevice(7L,"meter","meter",3L,1L,1L,"1",1,false,"MONITOR",5,BigDecimal.valueOf(80));
        new AlarmEvaluateService(rules,states).evaluate(device,List.of(),Instant.now());
        verifyNoInteractions(states);
        verify(rules,never()).upsertAlarm(any(),any(),any(),any(),any(),any());
    }
}
