package com.parkenergydata.service.ingest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.*;
import com.parkenergydata.entity.*;
import com.parkenergydata.parser.JsonPointParser;
import com.parkenergydata.service.*;
import org.springframework.stereotype.Service;

@Service
public class TelemetryPreparationService {
    private static final int QUALITY_NORMAL = 0;
    private static final int QUALITY_ROLLBACK = 1;
    private final IngestDeviceCache deviceCache;
    private final DeviceMetadataService metadataService;
    private final JsonPointParser pointParser;

    public TelemetryPreparationService(IngestDeviceCache deviceCache,
            DeviceMetadataService metadataService, JsonPointParser pointParser) {
        this.deviceCache = deviceCache;
        this.metadataService = metadataService;
        this.pointParser = pointParser;
    }

    public PreparedTelemetry prepare(AccessForwardMessage forward, GatewayUploadPayload payload, MeterPayload meter) {
        if (meter == null) throw new BusinessException("Meter is missing");
        if (meter.deviceSn() == null || meter.deviceSn().isBlank()) {
            throw new BusinessException("Meter deviceSn is missing: " + forward.messageId());
        }
        Instant collectTime = resolveCollectTime(meter, payload, forward);
        validateQuality(meter, collectTime, forward.messageId());
        boolean rollback = meter.quality() != null && meter.quality() == QUALITY_ROLLBACK;
        DevDevice device = deviceCache.findEnabled(forward.gatewayId(), meter.deviceSn())
                .orElseThrow(() -> new BusinessException("Device not found or disabled: " + meter.deviceSn()));
        Map<String, DevPointDefinition> definitions = metadataService.definitions(device.deviceTypeId());
        if (definitions.isEmpty()) {
            throw new BusinessException("Point definition is empty for device type: " + device.deviceTypeId());
        }
        List<ParsedPoint> points;
        try {
            points = pointParser.parse(meter, definitions);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Device " + meter.deviceSn() + ": " + ex.getMessage(), ex);
        }
        if (points.isEmpty()) {
            throw new BusinessException("No parsed points for device: " + meter.deviceSn());
        }
        validatePointValues(meter.deviceSn(), points);
        return new PreparedTelemetry(device, meter, collectTime, forward.receivedAt(), points,
                definitions, effectiveSampleInterval(meter, payload), payload.reportWindowSeconds(), rollback);
    }

    public Instant resolveCollectTime(MeterPayload meter, GatewayUploadPayload payload, AccessForwardMessage forward) {
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

    private Integer effectiveSampleInterval(MeterPayload meter, GatewayUploadPayload payload) {
        if (meter.sampleIntervalSeconds() != null && meter.sampleIntervalSeconds() > 0) {
            return meter.sampleIntervalSeconds();
        }
        return payload.sampleIntervalSeconds() != null && payload.sampleIntervalSeconds() > 0
                ? payload.sampleIntervalSeconds() : null;
    }

    private void validateQuality(MeterPayload meter, Instant collectTime, String messageId) {
        if (meter.quality() != null
                && meter.quality() != QUALITY_NORMAL
                && meter.quality() != QUALITY_ROLLBACK) {
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
