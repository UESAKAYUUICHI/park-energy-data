package com.parkenergydata.timeseries;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.parkenergydata.dto.ParsedPoint;

public interface TimeSeriesWriter {
    void writeDevicePoints(Long deviceId, Instant collectTime, List<ParsedPoint> points);

    List<Map<String, Object>> queryHistory(Long deviceId, String pointCode, String startTime, String endTime);

    default List<Map<String, Object>> queryHistory(Long deviceId, String pointCode, String startTime, String endTime, int limit) {
        return queryHistory(deviceId, pointCode, startTime, endTime);
    }
}
