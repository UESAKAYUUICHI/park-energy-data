package com.parkenergydata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergydata.cache.RealtimeCacheService;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;
import com.parkenergydata.entity.DevPointMapping;
import com.parkenergydata.parser.JsonPointParser;
import com.parkenergydata.repository.CollectionQualityRepository;
import com.parkenergydata.repository.DataIngestEventRepository;
import com.parkenergydata.repository.DataIngestItemRepository;
import com.parkenergydata.repository.DeviceRepository;
import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Offline execution of the points payload emitted by SimulatedGateway. */
@ExtendWith(MockitoExtension.class)
class DataIngestServiceTests {
    @Mock private DeviceRepository deviceRepository;
    @Mock private DataIngestEventRepository ingestEventRepository;
    @Mock private DataIngestItemRepository ingestItemRepository;
    @Mock private DeviceMetadataService metadataService;
    @Mock private RealtimeCacheService realtimeCacheService;
    @Mock private TimeSeriesWriter timeSeriesWriter;
    @Mock private DailyStatsService dailyStatsService;
    @Mock private HourlyStatsService hourlyStatsService;
    @Mock private TouStatsService touStatsService;
    @Mock private AlarmEvaluateService alarmEvaluateService;
    @Mock private CollectionQualityRepository collectionQualityRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DataIngestService service;

    @BeforeEach
    void setUp() {
        service = new DataIngestService(deviceRepository, ingestEventRepository, ingestItemRepository,
                metadataService, new JsonPointParser(objectMapper), realtimeCacheService, timeSeriesWriter,
                dailyStatsService, hourlyStatsService, touStatsService, alarmEvaluateService,
                collectionQualityRepository);
    }

    @Test
    void acceptsNewlyBoundMeterWhenReplayingMixedRawPayloadAndSkipsPreviouslySuccessfulMeter() throws Exception {
        Instant receivedAt = Instant.now();
        AccessForwardMessage replay = new AccessForwardMessage(901L, "REPLAY-901-1", 3L, "GW-DEMO-003",
                "DATA_UPLOAD", "{}", receivedAt);
        GatewayUploadPayload payload = mixedPayload(receivedAt.toEpochMilli());
        DevDevice newlyBound = device(102L, "METER-UNKNOWN");
        when(ingestEventRepository.tryClaim(replay)).thenReturn(true);
        when(ingestItemRepository.tryClaim(eq(replay), eq("METER-KNOWN"), any())).thenReturn(false);
        when(ingestItemRepository.tryClaim(eq(replay), eq("METER-UNKNOWN"), any())).thenReturn(true);
        when(deviceRepository.findEnabledByGatewayAndSn(3L, "METER-UNKNOWN")).thenReturn(Optional.of(newlyBound));
        stubVoltageMapping();

        service.ingest(replay, payload);

        ArgumentCaptor<Long> deviceId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<List<ParsedPoint>> points = ArgumentCaptor.forClass(List.class);
        verify(timeSeriesWriter).writeDevicePoints(deviceId.capture(), any(), points.capture());
        verify(hourlyStatsService).updateHourly(eq(newlyBound), any(), any());
        assertThat(deviceId.getValue()).isEqualTo(102L);
        assertThat(points.getValue()).extracting(ParsedPoint::pointCode).containsExactly("VOLTAGE_A");
        assertThat(points.getValue().get(0).numericValue()).isEqualByComparingTo("219.8");
        verify(ingestItemRepository).markSuccess(eq(replay), eq("METER-UNKNOWN"), any(), eq(102L));
        verify(ingestItemRepository, never()).markInvalid(eq(replay), anyString(), any(), anyString());
        verify(ingestEventRepository).markSuccess(replay, 1);
    }

    @Test
    void recordsADeviceFailureWithoutBlockingOtherMetersInTheSamePayload() throws Exception {
        Instant receivedAt = Instant.now();
        AccessForwardMessage forward = new AccessForwardMessage(902L, "MSG-3-2", 3L, "GW-DEMO-003",
                "DATA_UPLOAD", "{}", receivedAt);
        GatewayUploadPayload payload = mixedPayload(receivedAt.toEpochMilli());
        when(ingestEventRepository.tryClaim(forward)).thenReturn(true);
        when(ingestItemRepository.tryClaim(eq(forward), anyString(), any())).thenReturn(true);
        when(deviceRepository.findEnabledByGatewayAndSn(3L, "METER-KNOWN")).thenReturn(Optional.of(device(101L, "METER-KNOWN")));
        when(deviceRepository.findEnabledByGatewayAndSn(3L, "METER-UNKNOWN")).thenReturn(Optional.empty());
        stubVoltageMapping();

        service.ingest(forward, payload);

        verify(timeSeriesWriter).writeDevicePoints(eq(101L), any(), any());
        verify(ingestItemRepository).markSuccess(eq(forward), eq("METER-KNOWN"), any(), eq(101L));
        verify(ingestItemRepository).markInvalid(eq(forward), eq("METER-UNKNOWN"), any(), anyString());
        verify(ingestEventRepository).markSuccess(forward, 1);
    }

    private GatewayUploadPayload mixedPayload(long timestamp) throws Exception {
        return new GatewayUploadPayload("MSG-3-1", "GW-DEMO-003", timestamp, "DATA_UPLOAD", List.of(
                new MeterPayload("METER-KNOWN", 1, timestamp, 0, null,
                        objectMapper.readTree("{\"voltage_a\":221.4}"), null),
                new MeterPayload("METER-UNKNOWN", 2, timestamp, 0, null,
                        objectMapper.readTree("{\"voltage_a\":219.8}"), null)
        ), "1.0");
    }

    private void stubVoltageMapping() {
        DevPointDefinition definition = new DevPointDefinition(1L, 6L, "VOLTAGE_A", "A 相电压", "DOUBLE", "V", 1,
                "INSTANT_VALUE", false, true, true);
        DevPointMapping mapping = new DevPointMapping(1L, 6L, "VOLTAGE_A", "JSON", "$.points.voltage_a",
                null, null, null, null, null, BigDecimal.ONE, BigDecimal.ZERO, null, true);
        when(metadataService.definitions(anyLong())).thenReturn(Map.of("VOLTAGE_A", definition));
        when(metadataService.mappings(anyLong())).thenReturn(Map.of("VOLTAGE_A", mapping));
    }

    private DevDevice device(long id, String sn) {
        return new DevDevice(id, sn, sn, 3L, 8L, 6L, null, 1, false, "GENERAL", 300, BigDecimal.valueOf(95));
    }
}
