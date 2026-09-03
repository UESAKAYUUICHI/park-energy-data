package com.parkenergydata.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import com.parkenergydata.dto.RealtimePointValue;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.entity.DevPointMapping;
import com.parkenergydata.parser.JsonPointParser;
import com.parkenergydata.repository.CollectionQualityRepository;
import com.parkenergydata.repository.DataIngestEventRepository;
import com.parkenergydata.repository.DataIngestItemRepository;
import com.parkenergydata.repository.DeviceRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataIngestService {
    private final IngestDeviceCache deviceCache;
    private final GatewayDeviceRateLimiter rateLimiter;
    private final DataIngestEventRepository ingestEventRepository;
    private final DataIngestItemRepository ingestItemRepository;
    private final DeviceMetadataService metadataService;
    private final JsonPointParser pointParser;
    private final RealtimeCacheService realtimeCacheService;
    private final TimeSeriesWriter timeSeriesWriter;
    private final DailyStatsService dailyStatsService;
    private final HourlyStatsService hourlyStatsService;
    private final CollectionQualityRepository collectionQualityRepository;
    private final TouStatsService touStatsService;
    private final AlarmEvaluateService alarmEvaluateService;

    @Autowired
    public DataIngestService(IngestDeviceCache deviceCache, GatewayDeviceRateLimiter rateLimiter, DataIngestEventRepository ingestEventRepository,
                             DataIngestItemRepository ingestItemRepository, DeviceMetadataService metadataService,
                             JsonPointParser pointParser, RealtimeCacheService realtimeCacheService,
                             TimeSeriesWriter timeSeriesWriter, DailyStatsService dailyStatsService,
                             HourlyStatsService hourlyStatsService, TouStatsService touStatsService,
                             AlarmEvaluateService alarmEvaluateService,
                             CollectionQualityRepository collectionQualityRepository) {
        this.deviceCache = deviceCache;
        this.rateLimiter = rateLimiter;
        this.ingestEventRepository = ingestEventRepository;
        this.ingestItemRepository = ingestItemRepository;
        this.metadataService = metadataService;
        this.pointParser = pointParser;
        this.realtimeCacheService = realtimeCacheService;
        this.timeSeriesWriter = timeSeriesWriter;
        this.dailyStatsService = dailyStatsService;
        this.hourlyStatsService = hourlyStatsService;
        this.touStatsService = touStatsService;
        this.alarmEvaluateService = alarmEvaluateService;
        this.collectionQualityRepository = collectionQualityRepository;
    }

    /** Compatibility constructor retained for isolated unit tests and non-Spring callers. */
    public DataIngestService(DeviceRepository deviceRepository, DataIngestEventRepository ingestEventRepository,
                             DataIngestItemRepository ingestItemRepository, DeviceMetadataService metadataService,
                             JsonPointParser pointParser, RealtimeCacheService realtimeCacheService,
                             TimeSeriesWriter timeSeriesWriter, DailyStatsService dailyStatsService,
                             HourlyStatsService hourlyStatsService, TouStatsService touStatsService,
                             AlarmEvaluateService alarmEvaluateService,
                             CollectionQualityRepository collectionQualityRepository) {
        this(new IngestDeviceCache(deviceRepository), new GatewayDeviceRateLimiter(120), ingestEventRepository,
                ingestItemRepository, metadataService, pointParser, realtimeCacheService, timeSeriesWriter,
                dailyStatsService, hourlyStatsService, touStatsService, alarmEvaluateService, collectionQualityRepository);
    }

    @Transactional
    public void ingest(AccessForwardMessage forward, GatewayUploadPayload payload) {
        if (!ingestEventRepository.tryClaim(forward)) {
            return;
        }
        try {
            if (payload.meters() == null || payload.meters().isEmpty()) {
                throw new BusinessException("No meters in payload: " + forward.messageId());
            }
            int acceptedCount = 0;
            java.util.List<String> failedMeters = new java.util.ArrayList<>();
            for (MeterPayload meter : payload.meters()) {
                String deviceSn = meter == null ? null : meter.deviceSn();
                Instant collectTime = meter == null ? (forward.receivedAt() == null ? Instant.now() : forward.receivedAt())
                        : resolveCollectTime(meter, payload, forward);
                if (!ingestItemRepository.tryClaim(forward, deviceSn, collectTime)) {
                    continue;
                }
                try {
                    if (deviceSn == null || !rateLimiter.tryAcquire(forward.gatewayId(), deviceSn)) {
                        throw new BusinessException("Device report rate exceeded; replay this item later: " + deviceSn);
                    }
                    DevDevice device = processMeter(forward, payload, meter);
                    ingestItemRepository.markSuccess(forward, deviceSn, collectTime, device.id());
                    acceptedCount++;
                } catch (RuntimeException exception) {
                    ingestItemRepository.markInvalid(forward, deviceSn, collectTime, exception.getMessage());
                    failedMeters.add(deviceSn == null ? "<missing SN>" : deviceSn);
                }
            }
            if (acceptedCount == 0) {
                ingestEventRepository.markInvalid(forward,
                        "No device samples were accepted: " + String.join(", ", failedMeters));
                return;
            }
            ingestEventRepository.markSuccess(forward, acceptedCount);
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    @Transactional
    public void recordInvalid(AccessForwardMessage forward, String reason) {
        ingestEventRepository.markInvalid(forward, reason);
    }

    @Transactional
    public void recordDeadLetter(AccessForwardMessage forward, String reason) {
        ingestEventRepository.markDeadLetter(forward, reason);
    }

    @Transactional
    public boolean requestReplay(long eventId) {
        return ingestEventRepository.requestReplay(eventId);
    }

    private DevDevice processMeter(AccessForwardMessage forward, GatewayUploadPayload payload, MeterPayload meter) {
        if (meter.deviceSn() == null || meter.deviceSn().isBlank()) {
            throw new BusinessException("Meter deviceSn is missing: " + forward.messageId());
        }
        Instant collectTime = resolveCollectTime(meter, payload, forward);
        validateQuality(meter, collectTime, forward.messageId());
        DevDevice device = deviceCache.findEnabled(forward.gatewayId(), meter.deviceSn())
                .orElseThrow(() -> new BusinessException("Device not found or disabled: " + meter.deviceSn()));
        Map<String, DevPointDefinition> definitions = metadataService.definitions(device.deviceTypeId());
        Map<String, DevPointMapping> mappings = metadataService.mappings(device.deviceTypeId());
        if (definitions.isEmpty() || mappings.isEmpty()) {
            throw new BusinessException("Point definition or mapping is empty for device type: " + device.deviceTypeId());
        }
        List<ParsedPoint> points;
        try {
            points = pointParser.parse(meter, definitions, mappings);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Device " + meter.deviceSn() + ": " + ex.getMessage(), ex);
        }
        if (points.isEmpty()) {
            throw new BusinessException("No parsed points for device: " + meter.deviceSn());
        }
        validatePointValues(meter.deviceSn(), points);
        timeSeriesWriter.writeDevicePoints(device.id(), collectTime, points);
        realtimeCacheService.saveRealtime(snapshot(device, meter, collectTime, forward.receivedAt(), points, definitions));
        dailyStatsService.updateDaily(device, collectTime, points);
        hourlyStatsService.updateHourly(device, collectTime, points);
        collectionQualityRepository.recordAcceptedMeter(device, collectTime);
        touStatsService.updateTou(device, collectTime, points, definitions);
        alarmEvaluateService.evaluate(device, points, collectTime);
        return device;
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
        String quality = meter.quality() == null || meter.quality() == 0 ? "NORMAL" : "ABNORMAL";
        return new RealtimeDeviceSnapshot(device.id(), device.deviceSn(), device.gatewayId(), device.orgId(),
                collectTime, receiveTime, delaySeconds, freshness, quality, values, details, meter.quality());
    }

    private Instant resolveCollectTime(MeterPayload meter, GatewayUploadPayload payload, AccessForwardMessage forward) {
        if (meter.collectTime() != null) {
            return Instant.ofEpochMilli(meter.collectTime());
        }
        if (payload.timestamp() != null) {
            return Instant.ofEpochMilli(payload.timestamp());
        }
        if (forward.receivedAt() != null) {
            return forward.receivedAt();
        }
        return Instant.now();
    }

    private void validateQuality(MeterPayload meter, Instant collectTime, String messageId) {
        if (meter.quality() != null && meter.quality() != 0) {
            throw new BusinessException("Meter quality is abnormal for " + meter.deviceSn() + ": " + meter.quality());
        }
        Instant now = Instant.now();
        if (collectTime.isAfter(now.plusSeconds(10 * 60L))) {
            throw new BusinessException("Collect time is too far in future for " + meter.deviceSn() + ": " + messageId);
        }
        if (collectTime.isBefore(now.minusSeconds(90L * 24 * 3600))) {
            throw new BusinessException("Collect time is older than 90 days for " + meter.deviceSn() + ": " + messageId);
        }
    }

    private void validatePointValues(String deviceSn, List<ParsedPoint> points) {
        for (ParsedPoint point : points) {
            if (point.numericValue() == null) {
                continue;
            }
            java.math.BigDecimal min = null;
            java.math.BigDecimal max = null;
            if (point.pointCode().startsWith("VOLTAGE_")) {
                min = java.math.BigDecimal.ZERO;
                max = java.math.BigDecimal.valueOf(500);
            } else if (point.pointCode().startsWith("CURRENT_")) {
                min = java.math.BigDecimal.ZERO;
                max = java.math.BigDecimal.valueOf(10000);
            } else if ("POWER_FACTOR_TOTAL".equals(point.pointCode())) {
                min = java.math.BigDecimal.valueOf(-1);
                max = java.math.BigDecimal.ONE;
            } else if ("FREQUENCY".equals(point.pointCode())) {
                min = java.math.BigDecimal.valueOf(45);
                max = java.math.BigDecimal.valueOf(55);
            } else if ("FORWARD_ACTIVE_ENERGY".equals(point.pointCode())) {
                min = java.math.BigDecimal.ZERO;
            }
            if ((min != null && point.numericValue().compareTo(min) < 0)
                    || (max != null && point.numericValue().compareTo(max) > 0)) {
                throw new BusinessException("Point value is out of range for " + deviceSn + ": "
                        + point.pointCode() + "=" + point.numericValue());
            }
        }
    }
}
