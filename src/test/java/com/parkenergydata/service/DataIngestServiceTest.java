package com.parkenergydata.service;

import java.time.Instant;
import java.util.List;
import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.*;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.repository.*;
import com.parkenergydata.service.ingest.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataIngestServiceTest {
    private final GatewayDeviceRateLimiter limiter = mock(GatewayDeviceRateLimiter.class);
    private final DataIngestEventRepository events = mock(DataIngestEventRepository.class);
    private final DataIngestItemRepository items = mock(DataIngestItemRepository.class);
    private final TelemetryPreparationService preparation = mock(TelemetryPreparationService.class);
    private final TelemetryProjectionService projection = mock(TelemetryProjectionService.class);
    private final DataIngestService service = new DataIngestService(limiter, events, items, preparation, projection);
    private final AccessForwardMessage forward = new AccessForwardMessage(1, "batch", 1L, "gateway",
            "DATA_UPLOAD", "{}", Instant.parse("2026-11-05T00:00:00Z"));
    private final MeterPayload meter = mock(MeterPayload.class);
    private final Instant collected = forward.receivedAt();
    private GatewayUploadPayload payload;

    @BeforeEach void setUp() {
        when(meter.deviceSn()).thenReturn("meter");
        payload = new GatewayUploadPayload("batch", "gateway", collected.toEpochMilli(), "DATA_UPLOAD", List.of(meter), "1");
        when(events.tryClaim(forward)).thenReturn(true);
        when(items.tryClaim(forward, meter, collected)).thenReturn(true);
        when(preparation.resolveCollectTime(meter, payload, forward)).thenReturn(collected);
        when(limiter.tryAcquire(1L, "meter")).thenReturn(true);
    }

    @Test void infrastructureFailureMustEscapeForRetry() {
        PreparedTelemetry sample = mock(PreparedTelemetry.class);
        when(preparation.prepare(forward, payload, meter)).thenReturn(sample);
        doThrow(new IllegalStateException("IoTDB unavailable")).when(projection).project(sample);
        assertThrows(IllegalStateException.class, () -> service.ingest(forward, payload));
        verify(items, never()).markInvalid(any(), any(), any(), any());
        verify(events, never()).markSuccess(any(), anyInt());
    }

    @Test void invalidInputIsRecordedWithoutProjection() {
        when(preparation.prepare(forward, payload, meter)).thenThrow(new BusinessException("required point missing"));
        service.ingest(forward, payload);
        verify(items).markInvalid(forward, "meter", collected, "required point missing");
        verifyNoInteractions(projection);
    }

    @Test void duplicateItemDoesNotBecomeInvalid() {
        when(items.tryClaim(forward, meter, collected)).thenReturn(false);
        service.ingest(forward, payload);
        verify(events).markSuccess(forward, 1);
        verify(events, never()).markInvalid(any(), any());
        verifyNoInteractions(projection);
    }

    @Test void rateLimitIsRecoverableInsteadOfInvalid() {
        when(limiter.tryAcquire(1L, "meter")).thenReturn(false);
        assertThrows(RecoverableIngestException.class, () -> service.ingest(forward, payload));
        verify(items, never()).markInvalid(any(), any(), any(), any());
    }

    @Test void historicalSamplesForOneDeviceShareOneBatchRatePermit() {
        MeterPayload second = mock(MeterPayload.class);
        when(second.deviceSn()).thenReturn("meter");
        payload = new GatewayUploadPayload("batch", "gateway", collected.toEpochMilli(), "DATA_UPLOAD",
                List.of(meter, second), "1");
        when(preparation.resolveCollectTime(meter, payload, forward)).thenReturn(collected);
        when(preparation.resolveCollectTime(second, payload, forward)).thenReturn(collected.plusSeconds(5));
        when(items.tryClaim(forward, second, collected.plusSeconds(5))).thenReturn(true);
        PreparedTelemetry firstSample = sample();
        PreparedTelemetry secondSample = sample();
        when(preparation.prepare(forward, payload, meter)).thenReturn(firstSample);
        when(preparation.prepare(forward, payload, second)).thenReturn(secondSample);
        service.ingest(forward, payload);
        verify(limiter, times(1)).tryAcquire(1L, "meter");
        verify(items).markSuccess(forward, "meter", collected, 9L);
        verify(items).markSuccess(forward, "meter", collected.plusSeconds(5), 9L);
    }

    private PreparedTelemetry sample() {
        PreparedTelemetry sample = mock(PreparedTelemetry.class);
        DevDevice device = mock(DevDevice.class);
        when(device.id()).thenReturn(9L);when(sample.device()).thenReturn(device);
        return sample;
    }
}
