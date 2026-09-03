package com.parkenergydata.service;

import com.parkenergydata.common.BusinessException;
import com.parkenergydata.dto.ParsePreviewRequest;
import com.parkenergydata.dto.PointParsePreview;
import com.parkenergydata.entity.DevDevice;
import com.parkenergydata.parser.JsonPointParser;
import com.parkenergydata.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataParsePreviewService {
    private final DeviceRepository deviceRepository;
    private final DeviceMetadataService metadataService;
    private final JsonPointParser pointParser;

    public DataParsePreviewService(DeviceRepository deviceRepository, DeviceMetadataService metadataService,
                                   JsonPointParser pointParser) {
        this.deviceRepository = deviceRepository;
        this.metadataService = metadataService;
        this.pointParser = pointParser;
    }

    public Map<String, Object> preview(ParsePreviewRequest request) {
        if (request == null || request.deviceId() == null || request.meter() == null) {
            throw new BusinessException("deviceId 和 meter 不能为空");
        }
        DevDevice device = deviceRepository.findEnabledList(request.deviceId()).stream().findFirst()
                .orElseThrow(() -> new BusinessException("设备不存在或未启用: " + request.deviceId()));
        List<PointParsePreview> points = pointParser.preview(request.meter(),
                metadataService.definitions(device.deviceTypeId()), metadataService.mappings(device.deviceTypeId()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", device.id());
        result.put("deviceSn", device.deviceSn());
        result.put("deviceTypeId", device.deviceTypeId());
        result.put("points", points);
        result.put("successCount", points.stream().filter(item -> "SUCCESS".equals(item.status())).count());
        result.put("failedCount", points.stream().filter(item -> "FAILED".equals(item.status())).count());
        return result;
    }
}
