package com.parkenergydata.controller;

import com.parkenergydata.common.ApiResponse;
import com.parkenergydata.dto.ParsePreviewRequest;
import com.parkenergydata.service.DataParsePreviewService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/data/parse-preview")
public class DataParsePreviewController {
    private final DataParsePreviewService service;

    public DataParsePreviewController(DataParsePreviewService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> preview(@RequestBody ParsePreviewRequest request) {
        return ApiResponse.ok(service.preview(request));
    }
}
