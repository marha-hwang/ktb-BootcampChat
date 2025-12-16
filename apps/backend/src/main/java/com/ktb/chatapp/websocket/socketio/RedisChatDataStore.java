package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Redis-backed implementation of ChatDataStore using a single shared map.
 * Ensures Socket.IO state is shared across multiple application instances.
 */
@Slf4j
public class RedisChatDataStore implements ChatDataStore {

    private static final String STORE_KEY = "chatapp:socketio:datastore";

    private final ObjectMapper objectMapper;
    private final RMap<String, String> store;

    public RedisChatDataStore(ObjectMapper objectMapper, RedissonClient redissonClient) {
        this.objectMapper = objectMapper;
        this.store = redissonClient.getMap(STORE_KEY, StringCodec.INSTANCE);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String json = store.get(key);
        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Failed to deserialize value for key {} to type {}", key, type.getSimpleName(), e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            store.put(key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize value for key " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public int size() {
        return store.size();
    }
}
