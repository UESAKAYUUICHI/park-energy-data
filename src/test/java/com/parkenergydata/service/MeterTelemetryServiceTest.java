package com.parkenergydata.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.repository.CollectionQualityRepository;
import com.parkenergydata.repository.CollectionWindowQualityRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeterTelemetryServiceTest {
    @Mock RealtimeCacheService realtimeCacheService;
    @Mock TimeSeriesWriter timeSeriesWriter;
    @Mock DailyStatsService dailyStatsService;
    @Mock HourlyStatsService hourlyStatsService;
    @Mock CollectionQualityRepository collectionQualityRepository;
    @Mock CollectionWindowQualityRepository collectionWindowQualityRepository;
    @Mock TouStatsService touStatsService;
    @Mock AlarmEvaluateService alarmEvaluateService;

    private MeterTelemetryService service;
    private AccessForwardMessage forward;
    private GatewayUploadPayload payload;
    private MeterPayload meter;
    private DevDevice device;
    private List<ParsedPoint> points;
    private Map<String, DevPointDefinition> definitions;
    private Instant collectTime;

    @BeforeEach
    void setUp() {
        service = new MeterTelemetryService(realtimeCacheService, timeSeriesWriter, dailyStatsService,
                hourlyStatsService, collectionQualityRepository, collectionWindowQualityRepository,
                touStatsService, alarmEvaluateService);
        collectTime = Instant.parse("2026-09-28T06:00:00Z");
        forward = new AccessForwardMessage(1L, "message-1", 2L, "gateway-1", "DATA_UPLOAD", "{}", collectTime);
        meter = new MeterPayload("meter-1", 1, collectTime.toEpochMilli(), 60, 0, null, null, null);
        payload = new GatewayUploadPayload("message-1", "gateway-1", collectTime.toEpochMilli(),
                "DATA_UPLOAD", 60, 300, List.of(meter), "1.0");
        device = new DevDevice(3L, "meter-1", "Meter", 2L, 4L, 5L, "1", 1,
                true, "MAIN", 60, BigDecimal.valueOf(95));
        ParsedPoint point = new ParsedPoint("ACTIVE_POWER_TOTAL", "DOUBLE", "POWER", true,
                1.2D, BigDecimal.valueOf(1.2));
        points = List.of(point);
        definitions = Map.of("ACTIVE_POWER_TOTAL", new DevPointDefinition(
                10L, 20L, "ACTIVE_POWER_TOTAL", "Active power", "DOUBLE", "kW", 2,
                "POWER", false, true, true));
    }

    @Test
    void persistsNormalSampleToAllTelemetryViews() {
        service.persist(forward, payload, meter, device, collectTime, points, definitions);

        verify(timeSeriesWriter).writeDevicePoints(3L, collectTime, points);
        verify(realtimeCacheService).saveRealtime(any());
        verify(dailyStatsService).updateDaily(device, collectTime, points);
        verify(hourlyStatsService).updateHourly(device, collectTime, points);
        verify(collectionQualityRepository).recordAcceptedMeter(device, collectTime);
        verify(collectionWindowQualityRepository).record(device, collectTime, 60, 300);
        verify(touStatsService).updateTou(device, collectTime, points, definitions);
        verify(alarmEvaluateService).evaluate(device, points, collectTime);
    }

    @Test
    void persistsRollbackSnapshotButSkipsDerivedStatistics() {
        MeterPayload rollback = new MeterPayload("meter-1", 1, collectTime.toEpochMilli(), 60, 1,
                null, null, null);

        service.persist(forward, payload, rollback, device, collectTime, points, definitions);

        verify(timeSeriesWriter).writeDevicePoints(3L, collectTime, points);
        verify(realtimeCacheService).saveRealtime(any());
        verify(dailyStatsService, never()).updateDaily(device, collectTime, points);
        verify(hourlyStatsService, never()).updateHourly(device, collectTime, points);
        verify(collectionQualityRepository, never()).recordAcceptedMeter(device, collectTime);
        verify(collectionWindowQualityRepository, never()).record(any(), any(), any(), any());
        verify(touStatsService, never()).updateTou(device, collectTime, points, definitions);
        verify(alarmEvaluateService, never()).evaluate(device, points, collectTime);
    }
}
