package com.parkenergydata.controller;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import com.parkenergydata.service.RealtimeDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
