package com.parkenergydata.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.repository.DataIngestEventRepository;
import com.parkenergydata.repository.DataIngestItemRepository;
import com.parkenergydata.service.ingest.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataIngestService {
    private final GatewayDeviceRateLimiter rateLimiter;
    private final DataIngestEventRepository events;
    private final DataIngestItemRepository items;
    private final TelemetryPreparationService preparation;
    private final TelemetryProjectionService projection;

    public DataIngestService(GatewayDeviceRateLimiter rateLimiter, DataIngestEventRepository events,
                             DataIngestItemRepository items, TelemetryPreparationService preparation,
                             TelemetryProjectionService projection) {
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.items = items;
        this.preparation = preparation;
        this.projection = projection;
    }

    @Transactional
    public void ingest(AccessForwardMessage forward, GatewayUploadPayload payload) {
        if (!events.tryClaim(forward)) return;
        if (payload.meters() == null || payload.meters().isEmpty()) {
            throw new BusinessException("No meters in payload: " + forward.messageId());
        }
        int accepted = 0;
        int duplicates = 0;
        var rejected = new ArrayList<String>();
        Set<String> checkedDevices = new HashSet<>();
        for (MeterPayload meter : payload.meters()) {
            String sn = meter == null ? null : meter.deviceSn();
            Instant time = meter == null ? forward.receivedAt()
                    : preparation.resolveCollectTime(meter, payload, forward);
            if (!items.tryClaim(forward, meter, time)) { duplicates++; continue; }
            if (sn != null && checkedDevices.add(sn) && !rateLimiter.tryAcquire(forward.gatewayId(), sn)) {
                throw new RecoverableIngestException("Device report rate exceeded: " + sn);
            }
            PreparedTelemetry sample;
            try {
                sample = preparation.prepare(forward, payload, meter);
            } catch (BusinessException | IllegalArgumentException invalid) {
                items.markInvalid(forward, sn, time, invalid.getMessage());
                rejected.add(sn == null ? "<missing SN>" : sn);
                continue;
            }
            try { projection.project(sample); }
            catch(SampleConflictException conflict) {
                items.markInvalid(forward,sn,time,conflict.getMessage());
                rejected.add(sn);
                continue;
            }
            items.markSuccess(forward, sn, time, sample.device().id());
            accepted++;
        }
        if (accepted + duplicates == 0) {
            events.markInvalid(forward, "No device samples were accepted: " + String.join(", ", rejected));
        } else {
            events.markSuccess(forward, accepted + duplicates);
        }
    }

    @Transactional public void recordInvalid(AccessForwardMessage forward, String reason) { events.markInvalid(forward, reason); }
    @Transactional public void recordDeadLetter(AccessForwardMessage forward, String reason) { events.markDeadLetter(forward, reason); }
    @Transactional public boolean requestReplay(long eventId) { return events.requestReplay(eventId); }
}
