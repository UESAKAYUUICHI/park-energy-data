package com.parkenergydata.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.AlarmRule;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.repository.AlarmRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlarmEvaluateServiceTests {
    @Mock private AlarmRuleRepository repository;
    @Mock private RealtimeCacheService cache;
    private AlarmEvaluateService service;
    private final DevDevice device = new DevDevice(7L, "METER-7", "Meter 7", 3L, 1L, 6L,
            "1", 1, true, "SETTLEMENT", 300, new BigDecimal("95"));
    private final AlarmRule rule = new AlarmRule(9L, "Over voltage", 1, 3, null, 7L,
            "VOLTAGE_A", ">", new BigDecimal("240"), null, null, 30, 2, true);

    @BeforeEach
    void setUp() {
        service = new AlarmEvaluateService(repository, cache);
        when(repository.findEnabledForDevice(7L, 1L)).thenReturn(List.of(rule));
        lenient().when(cache.acceptAlarmSample(eq(7L), anyLong(), any(Instant.class), anyInt())).thenReturn(true);
    }

    @Test
    void waitsForConfiguredContinuousDurationBeforeCreatingEvent() {
        when(cache.alarmViolationDurationReached(eq(7L), eq(9L), eq(30), any(Instant.class))).thenReturn(false);
        service.evaluate(device, List.of(point("250")));
        verify(repository, never()).upsertAlarm(eq(rule), eq(7L), eq(1L), eq("250"), eq("240"), any(Instant.class));
    }

    @Test
    void createsOneActiveEventAfterDurationAndWritesTriggerTimeline() {
        when(cache.alarmViolationDurationReached(eq(7L), eq(9L), eq(30), any(Instant.class))).thenReturn(true);
        when(cache.alarmRecentlyTriggered(7L, 9L)).thenReturn(false);
        when(repository.findActiveAlarmId(9L, 7L)).thenReturn(null, 88L);
        service.evaluate(device, List.of(point("250")));
        verify(repository).upsertAlarm(eq(rule), eq(7L), eq(1L), eq("250"), eq("240"), any(Instant.class));
        verify(repository).insertEventLog(88L, "TRIGGER", null, "NEW", "测点 VOLTAGE_A 触发规则，判定值 250");
    }

    @Test
    void marksActiveEventRecoveredOnlyAfterConfiguredNormalSamples() {
        when(repository.findActiveAlarmId(9L, 7L)).thenReturn(88L);
        when(repository.findAlarmStatus(88L)).thenReturn("IN_PROGRESS");
        when(cache.alarmRecoverySamplesReached(7L, 9L, 3)).thenReturn(true);
        when(repository.recoverAlarm(eq(88L), eq("230"), any(Instant.class))).thenReturn(1);
        service.evaluate(device, List.of(point("230")));
        verify(repository).insertEventLog(88L, "AUTO_RECOVER", "IN_PROGRESS", "RECOVERED",
                "测点连续 3 次满足恢复条件，当前值 230");
    }

    @Test
    void remainsCompatibleWithLegacyGtOperator() {
        AlarmRule legacy = new AlarmRule(10L, "Legacy", 1, 3, null, 7L,
                "VOLTAGE_A", "GT", new BigDecimal("240"), null, null, 0, 2, true);
        when(repository.findEnabledForDevice(7L, 1L)).thenReturn(List.of(legacy));
        when(cache.alarmViolationDurationReached(eq(7L), eq(10L), eq(0), any(Instant.class))).thenReturn(true);
        when(repository.findActiveAlarmId(10L, 7L)).thenReturn(null, 89L);
        service.evaluate(device, List.of(point("250")));
        verify(repository).upsertAlarm(eq(legacy), eq(7L), eq(1L), eq("250"), eq("240"), any(Instant.class));
    }

    @Test
    void rejectsOutOfOrderSampleBeforeChangingAlarmState() {
        when(cache.acceptAlarmSample(eq(7L), eq(9L), any(Instant.class), anyInt())).thenReturn(false);
        service.evaluate(device, List.of(point("250")), Instant.now().minusSeconds(10));
        verify(repository, never()).upsertAlarm(eq(rule), eq(7L), eq(1L), eq("250"), eq("240"), any(Instant.class));
    }

    private ParsedPoint point(String value) {
        BigDecimal number = new BigDecimal(value);
        return new ParsedPoint("VOLTAGE_A", "DOUBLE", "INSTANT_VALUE", true, number, number);
    }
}
