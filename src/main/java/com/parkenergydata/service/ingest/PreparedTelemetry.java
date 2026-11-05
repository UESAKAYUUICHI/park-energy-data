package com.parkenergydata.service.ingest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.parkenergydata.dto.MeterPayload;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.entity.DevPointDefinition;

public record PreparedTelemetry(DevDevice device, MeterPayload meter, Instant collectedAt,
                                Instant receivedAt, List<ParsedPoint> points,
                                Map<String, DevPointDefinition> definitions,
                                Integer sampleIntervalSeconds, Integer reportWindowSeconds,
                                boolean rollback) { }
