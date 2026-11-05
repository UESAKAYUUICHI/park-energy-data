package com.parkenergydata.cache;

import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkenergydata.config.ParkDataProperties;
import com.parkenergydata.dto.RealtimeDeviceSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RealtimeCacheService {
    private static final DefaultRedisScript<Long> SAVE_NEWEST = new DefaultRedisScript<>("""
            local function normalized(t)
              if type(t) ~= 'string' then return nil end
              local fraction=string.match(t,'%.(%d+)') or ''
              return string.sub(t,1,19)..'.'..string.sub(fraction..'000000000',1,9)
            end
            local incoming=cjson.decode(ARGV[1])
            local current=redis.call('GET',KEYS[1])
            if current then
              local ok,decoded=pcall(cjson.decode,current)
              if ok then
                local old=normalized(decoded.collectTime)
                local next=normalized(incoming.collectTime)
                if old and next and old>next then return 0 end
              end
            end
            redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2])
            return 1
            """,Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ParkDataProperties properties;

    public RealtimeCacheService(StringRedisTemplate redis,ObjectMapper json,ParkDataProperties properties) {
        this.redis=redis; this.json=json; this.properties=properties;
    }
    public void saveRealtime(RealtimeDeviceSnapshot snapshot) {
        try {
            String value=json.writeValueAsString(snapshot);
            redis.execute(SAVE_NEWEST,List.of(key(snapshot.deviceId())),value,
                    String.valueOf(properties.realtimeTtl().toMillis()));
        } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize realtime snapshot",e); }
    }
    public Optional<RealtimeDeviceSnapshot> findRealtime(Long deviceId) {
        String value=redis.opsForValue().get(key(deviceId));
        if(value==null) return Optional.empty();
        try { return Optional.of(json.readValue(value,RealtimeDeviceSnapshot.class)); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Cannot deserialize realtime snapshot",e); }
    }
    private String key(Long deviceId) { return "realtime:device:"+deviceId; }
}
