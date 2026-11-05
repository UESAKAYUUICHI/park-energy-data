package com.parkenergydata.service.ingest;

import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="park.data.projection-enabled",matchIfMissing=true)
public class TelemetryProjectionWorker {
    private static final Logger log=LoggerFactory.getLogger(TelemetryProjectionWorker.class);
    private final TelemetryOutboxRepository outbox;
    private final TimeSeriesWriter history;
    private final RealtimeCacheService realtime;
    public TelemetryProjectionWorker(TelemetryOutboxRepository outbox,TimeSeriesWriter history,RealtimeCacheService realtime) {
        this.outbox=outbox; this.history=history; this.realtime=realtime;
    }
    @Scheduled(fixedDelayString="${park.data.projection-interval-ms:1000}")
    @Transactional
    public void projectPending() {
        for(var item:outbox.lockPending()) {
            try {
                TelemetryProjection value=outbox.decode(item.payload());
                history.writeDevicePoints(value.deviceId(),value.collectedAt(),value.points());
                realtime.saveRealtime(value.snapshot());
                outbox.complete(item.id());
            } catch(RuntimeException failure) {
                outbox.retry(item,failure);
                log.warn("Telemetry projection {} pending retry: {}",item.id(),failure.getClass().getSimpleName(),failure);
            }
        }
    }
}
