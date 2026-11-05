package com.parkenergydata.service.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.*;
import com.parkenergydata.entity.*;
import com.parkenergydata.service.AlarmEvaluateService;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;

@Service
public class TelemetryProjectionService {
    private static final int QUALITY_NORMAL = 0;
    private static final int QUALITY_ROLLBACK = 1;
    private final SampleReceiptRepository receipts;
    private final TelemetryOutboxRepository outbox;
    private final EnergyStatisticsUpdater statistics;
    private final AlarmEvaluateService alarms;

    public TelemetryProjectionService(SampleReceiptRepository receipts,TelemetryOutboxRepository outbox,
            EnergyStatisticsUpdater statistics,AlarmEvaluateService alarms) {
        this.receipts=receipts;
        this.outbox=outbox;
        this.statistics=statistics;
        this.alarms=alarms;
    }

    public void project(PreparedTelemetry sample) {
        if(!receipts.claim(sample)) return;
        if(!sample.rollback()) {
            statistics.update(sample);
            alarms.evaluate(sample.device(),sample.points(),sample.collectedAt());
        }
        outbox.append(new TelemetryProjection(sample.device().id(),sample.collectedAt(),sample.points(),
                snapshot(sample.device(),sample.meter(),sample.collectedAt(),sample.receivedAt(),
                        sample.points(),sample.definitions())));
    }

    private RealtimeDeviceSnapshot snapshot(DevDevice device, MeterPayload meter, Instant collectTime,
                                            Instant receivedAt, List<ParsedPoint> points,
                                            Map<String, DevPointDefinition> definitions) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<RealtimePointValue> details = new java.util.ArrayList<>();
        for (ParsedPoint point : points) {
            values.put(point.pointCode(), point.value());
            DevPointDefinition definition = definitions.get(point.pointCode());
            details.add(new RealtimePointValue(point.pointCode(),
                    definition == null ? point.pointCode() : definition.pointName(), point.value(),
                    definition == null ? null : definition.unit(), point.businessRole(), "GOOD"));
        }
        Instant receiveTime = receivedAt == null ? Instant.now() : receivedAt;
        long delaySeconds = Math.max(0L, java.time.Duration.between(collectTime, receiveTime).getSeconds());
        long staleThreshold = Math.max(30L, (device.collectIntervalSeconds() == null ? 300L
                : device.collectIntervalSeconds().longValue()) * 3L);
        String freshness = delaySeconds > staleThreshold ? "STALE" : "FRESH";
        String quality = meter.quality() == null || meter.quality() == QUALITY_NORMAL
                ? "NORMAL"
                : meter.quality() == QUALITY_ROLLBACK ? "ROLLBACK" : "ABNORMAL";
        return new RealtimeDeviceSnapshot(device.id(), device.deviceSn(), device.gatewayId(), device.orgId(),
                collectTime, receiveTime, delaySeconds, freshness, quality, values, details, meter.quality());
    }

}
