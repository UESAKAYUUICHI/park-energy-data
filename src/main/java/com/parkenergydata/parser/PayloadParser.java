package com.parkenergydata.parser;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergydata.dto.AccessForwardMessage;
import com.parkenergydata.dto.GatewayUploadPayload;
import org.springframework.stereotype.Component;

@Component
public class PayloadParser {
    private final ObjectMapper objectMapper;

    public PayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AccessForwardMessage parseForwardMessage(String body) throws IOException {
        return objectMapper.readValue(body, AccessForwardMessage.class);
    }

    public GatewayUploadPayload parseGatewayPayload(String rawPayload) throws IOException {
        return objectMapper.readValue(rawPayload, GatewayUploadPayload.class);
    }
}
