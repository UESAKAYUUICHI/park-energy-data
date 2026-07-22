package com.parkenergydata.controller;

import java.util.List;
import java.util.Map;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.service.HistoryQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data/history")
public class HistoryDataController {
    private final HistoryQueryService historyQueryService;

    public HistoryDataController(HistoryQueryService historyQueryService) {
        this.historyQueryService = historyQueryService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> history(@RequestParam Long deviceId,
                                                          @RequestParam(required = false) String pointCode,
                                                          @RequestParam(required = false) String startTime,
                                                          @RequestParam(required = false) String endTime) {
        return ApiResponse.ok(historyQueryService.queryHistory(deviceId, pointCode, startTime, endTime));
    }
}
