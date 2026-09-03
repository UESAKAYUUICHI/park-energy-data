package com.parkenergydata.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import com.parkenergydata.service.RealtimeDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data/realtime")
public class RealtimeDataController {
    private final RealtimeDataService realtimeDataService;

    public RealtimeDataController(RealtimeDataService realtimeDataService) {
        this.realtimeDataService = realtimeDataService;
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<RealtimeDeviceSnapshot> device(@PathVariable Long deviceId) {
        return ApiResponse.ok(realtimeDataService.findRealtime(deviceId).orElse(null));
    }

    @GetMapping("/devices")
    public ApiResponse<Map<String, RealtimeDeviceSnapshot>> devices(@RequestParam String deviceIds) {
        List<Long> ids = Arrays.stream(deviceIds.split(","))
                .filter(value -> !value.isBlank())
                .map(value -> Long.valueOf(value.trim()))
                .distinct()
                .limit(200)
                .toList();
        return ApiResponse.ok(realtimeDataService.findRealtimeBatch(ids));
    }
}
