package com.parkenergydata.service.ingest;
import java.time.Instant;
import java.util.List;
import com.parkenergydata.dto.ParsedPoint;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;

public record TelemetryProjection(Long deviceId,Instant collectedAt,List<ParsedPoint> points,
                                  RealtimeDeviceSnapshot snapshot) {}
