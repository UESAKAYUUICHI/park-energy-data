package com.parkenergydata.service;

import java.util.List;
import java.util.Map;

import com.parkenergydata.timeseries.TimeSeriesWriter;
import org.springframework.stereotype.Service;

@Service
public class HistoryQueryService {
    private final TimeSeriesWriter timeSeriesWriter;

    public HistoryQueryService(TimeSeriesWriter timeSeriesWriter) {
        this.timeSeriesWriter = timeSeriesWriter;
    }

    public List<Map<String, Object>> queryHistory(Long deviceId, String pointCode, String startTime, String endTime) {
        return timeSeriesWriter.queryHistory(deviceId, pointCode, startTime, endTime);
    }
}
