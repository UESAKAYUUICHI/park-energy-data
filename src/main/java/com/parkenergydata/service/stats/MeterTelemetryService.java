package com.parkenergydata.service.stats;
import com.parkenergydata.service.alarm.AlarmEvaluateService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import com.parkenergydata.dto.RealtimePointValue;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.repository.stats.CollectionQualityRepository;
import com.parkenergydata.repository.stats.CollectionWindowQualityRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;

/** Persists an accepted meter sample to the realtime, timeseries and statistics views. */
@Service
public class MeterTelemetryService {
    private final RealtimeCacheService realtimeCacheService;
    private final TimeSeriesWriter timeSeriesWriter;
    private final DailyStatsService dailyStatsService;
    private final HourlyStatsService hourlyStatsService;
    private final CollectionQualityRepository collectionQualityRepository;
    private final CollectionWindowQualityRepository collectionWindowQualityRepository;
    private final TouStatsService touStatsService;
    private final AlarmEvaluateService alarmEvaluateService;

    public MeterTelemetryService(RealtimeCacheService realtimeCacheService, TimeSeriesWriter timeSeriesWriter,
                                 DailyStatsService dailyStatsService, HourlyStatsService hourlyStatsService,
                                 CollectionQualityRepository collectionQualityRepository,
                                 CollectionWindowQualityRepository collectionWindowQualityRepository,
                                 TouStatsService touStatsService, AlarmEvaluateService alarmEvaluateService) {
        this.realtimeCacheService = realtimeCacheService;
        this.timeSeriesWriter = timeSeriesWriter;
        this.dailyStatsService = dailyStatsService;
        this.hourlyStatsService = hourlyStatsService;
        this.collectionQualityRepository = collectionQualityRepository;
        this.collectionWindowQualityRepository = collectionWindowQualityRepository;
        this.touStatsService = touStatsService;
        this.alarmEvaluateService = alarmEvaluateService;
    }

    public void persist(AccessForwardMessage forward, GatewayUploadPayload payload, MeterPayload meter,
                        DevDevice device, Instant collectTime, List<ParsedPoint> points,
                        Map<String, DevPointDefinition> definitions) {
        boolean rollback = meter.quality() != null && meter.quality() == 1;
        timeSeriesWriter.writeDevicePoints(device.id(), collectTime, points);
        realtimeCacheService.saveRealtime(snapshot(device, meter, collectTime, forward.receivedAt(), points, definitions));
        if (rollback) return;

        dailyStatsService.updateDaily(device, collectTime, points);
        hourlyStatsService.updateHourly(device, collectTime, points);
        collectionQualityRepository.recordAcceptedMeter(device, collectTime);
        if (collectionWindowQualityRepository != null) {
            collectionWindowQualityRepository.record(device, collectTime,
                    effectiveSampleInterval(meter, payload), payload.reportWindowSeconds());
        }
        touStatsService.updateTou(device, collectTime, points, definitions);
        alarmEvaluateService.evaluate(device, points, collectTime);
    }

    private RealtimeDeviceSnapshot snapshot(DevDevice device, MeterPayload meter, Instant collectTime,
                                            Instant receivedAt, List<ParsedPoint> points,
                                            Map<String, DevPointDefinition> definitions) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<RealtimePointValue> details = new ArrayList<>();
        for (ParsedPoint point : points) {
            values.put(point.pointCode(), point.value());
            DevPointDefinition definition = definitions.get(point.pointCode());
            details.add(new RealtimePointValue(point.pointCode(),
                    definition == null ? point.pointCode() : definition.pointName(), point.value(),
                    definition == null ? null : definition.unit(), point.businessRole(), "GOOD"));
        }
        Instant receiveTime = receivedAt == null ? Instant.now() : receivedAt;
        long delaySeconds = Math.max(0L, Duration.between(collectTime, receiveTime).getSeconds());
        long staleThreshold = Math.max(30L, (device.collectIntervalSeconds() == null ? 300L
                : device.collectIntervalSeconds().longValue()) * 3L);
        String freshness = delaySeconds > staleThreshold ? "STALE" : "FRESH";
        String quality = meter.quality() == null || meter.quality() == 0
                ? "NORMAL" : meter.quality() == 1 ? "ROLLBACK" : "ABNORMAL";
        return new RealtimeDeviceSnapshot(device.id(), device.deviceSn(), device.gatewayId(), device.orgId(),
                collectTime, receiveTime, delaySeconds, freshness, quality, values, details, meter.quality());
    }

    private Integer effectiveSampleInterval(MeterPayload meter, GatewayUploadPayload payload) {
        if (meter.sampleIntervalSeconds() != null && meter.sampleIntervalSeconds() > 0) {
            return meter.sampleIntervalSeconds();
        }
        return payload.sampleIntervalSeconds() != null && payload.sampleIntervalSeconds() > 0
                ? payload.sampleIntervalSeconds() : null;
    }
}
