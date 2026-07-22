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
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.entity.DevPointMapping;
import com.parkenergydata.parser.JsonPointParser;
import com.parkenergydata.repository.DeviceRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DataIngestService {
    private final DeviceRepository deviceRepository;
    private final DeviceMetadataService metadataService;
    private final JsonPointParser pointParser;
    private final RealtimeCacheService realtimeCacheService;
    private final TimeSeriesWriter timeSeriesWriter;
    private final DailyStatsService dailyStatsService;
    private final AlarmEvaluateService alarmEvaluateService;

    public DataIngestService(DeviceRepository deviceRepository, DeviceMetadataService metadataService,
                             JsonPointParser pointParser, RealtimeCacheService realtimeCacheService,
                             TimeSeriesWriter timeSeriesWriter, DailyStatsService dailyStatsService,
                             AlarmEvaluateService alarmEvaluateService) {
        this.deviceRepository = deviceRepository;
        this.metadataService = metadataService;
        this.pointParser = pointParser;
        this.realtimeCacheService = realtimeCacheService;
        this.timeSeriesWriter = timeSeriesWriter;
        this.dailyStatsService = dailyStatsService;
        this.alarmEvaluateService = alarmEvaluateService;
    }

    @Transactional
    public void ingest(AccessForwardMessage forward, GatewayUploadPayload payload) {
        if (realtimeCacheService.isProcessed(forward.messageId())) {
            return;
        }
        if (!realtimeCacheService.tryStartProcessing(forward.messageId())) {
            return;
        }
        try {
            if (payload.meters() == null || payload.meters().isEmpty()) {
                throw new BusinessException("No meters in payload: " + forward.messageId());
            }
            for (MeterPayload meter : payload.meters()) {
                processMeter(forward, payload, meter);
            }
            markProcessedAfterCommit(forward.messageId());
        } catch (RuntimeException ex) {
            realtimeCacheService.clearProcessing(forward.messageId());
            throw ex;
        }
    }

    private void markProcessedAfterCommit(String messageId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            realtimeCacheService.markProcessed(messageId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                realtimeCacheService.markProcessed(messageId);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    realtimeCacheService.clearProcessing(messageId);
                }
            }
        });
    }

    private void processMeter(AccessForwardMessage forward, GatewayUploadPayload payload, MeterPayload meter) {
        if (meter.deviceSn() == null || meter.deviceSn().isBlank()) {
            throw new BusinessException("Meter deviceSn is missing: " + forward.messageId());
        }
        DevDevice device = deviceRepository.findEnabledByGatewayAndSn(forward.gatewayId(), meter.deviceSn())
                .orElseThrow(() -> new BusinessException("Device not found or disabled: " + meter.deviceSn()));
        Map<String, DevPointDefinition> definitions = metadataService.definitions(device.deviceTypeId());
        Map<String, DevPointMapping> mappings = metadataService.mappings(device.deviceTypeId());
        if (definitions.isEmpty() || mappings.isEmpty()) {
            throw new BusinessException("Point definition or mapping is empty for device type: " + device.deviceTypeId());
        }
        List<ParsedPoint> points = pointParser.parse(meter, definitions, mappings);
        if (points.isEmpty()) {
            throw new BusinessException("No parsed points for device: " + meter.deviceSn());
        }
        Instant collectTime = resolveCollectTime(meter, payload, forward);
        timeSeriesWriter.writeDevicePoints(device.id(), collectTime, points);
        realtimeCacheService.saveRealtime(snapshot(device, meter, collectTime, points));
        dailyStatsService.updateDaily(device, collectTime, points);
        alarmEvaluateService.evaluate(device, points);
    }

    private RealtimeDeviceSnapshot snapshot(DevDevice device, MeterPayload meter, Instant collectTime, List<ParsedPoint> points) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ParsedPoint point : points) {
            values.put(point.pointCode(), point.value());
        }
        return new RealtimeDeviceSnapshot(device.id(), device.deviceSn(), device.gatewayId(), device.orgId(),
                collectTime, values, meter.quality());
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
}
