package com.ktb.chatapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserCacheService {

    private final UserRepository userRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "users::";
    private static final long TTL_MINUTES = 30;

    /**
     * 단건 사용자 조회 (캐시 적용)
     */
    public User getUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }

        String cacheKey = CACHE_PREFIX + userId;

        // 1. 캐시 조회
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return convertToUser(cached);
            }
        } catch (Exception e) {
            log.warn("Redis 조회 실패: {}", e.getMessage());
        }

        // 2. DB 조회
        User user = userRepository.findById(userId).orElse(null);

        // 3. 캐시 저장
        if (user != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, user, TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Redis 저장 실패: {}", e.getMessage());
            }
        }

        return user;
    }

    /**
     * 다건 사용자 조회 (Multi-Get Batch 최적화)
     * Redis MGET을 사용하여 네트워크 홉을 최소화하고, 없는 데이터만 DB에서 조회합니다.
     */
    public Map<String, User> getUsers(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> sortedIds = new ArrayList<>(userIds);
        List<String> cacheKeys = sortedIds.stream()
                .map(id -> CACHE_PREFIX + id)
                .collect(Collectors.toList());

        Map<String, User> resultMap = new HashMap<>();
        List<String> missingIds = new ArrayList<>();

        // 1. Redis Multi-Get
        List<Object> cachedValues = null;
        try {
            cachedValues = redisTemplate.opsForValue().multiGet(cacheKeys);
        } catch (Exception e) {
            log.warn("Redis MGET 실패: {}", e.getMessage());
        }

        // 2. 캐시 히트/미스 분류
        if (cachedValues != null && cachedValues.size() == sortedIds.size()) {
            for (int i = 0; i < sortedIds.size(); i++) {
                String id = sortedIds.get(i);
                Object value = cachedValues.get(i);

                if (value != null) {
                    resultMap.put(id, convertToUser(value));
                } else {
                    missingIds.add(id);
                }
            }
        } else {
            // Redis 실패 시 전부 DB에서 조회
            missingIds.addAll(sortedIds);
        }

        // 3. DB Batch 조회 (캐시 미스된 것만)
        if (!missingIds.isEmpty()) {
            List<User> dbUsers = userRepository.findAllById(missingIds);
            Map<String, User> dbUserMap = new HashMap<>();

            for (User user : dbUsers) {
                resultMap.put(user.getId(), user);
                dbUserMap.put(CACHE_PREFIX + user.getId(), user);
            }

            // 4. Redis Pipeline Update (비동기 처리 가능하면 좋지만 여기선 MultiSet으로 처리)
            if (!dbUserMap.isEmpty()) {
                try {
                    redisTemplate.opsForValue().multiSet(dbUserMap);
                    // TTL 설정은 multiSet에서 안되므로 개별 설정하거나 파이프라인 써야 함.
                    // 간단하게는 조회 시점에 만료된 걸 갱신하거나, 여기서는 일단 저장만 함.
                    // (엄밀한 TTL 관리를 위해선 파이프라인을 써야하지만 일단 생략)
                    for (String key : dbUserMap.keySet()) {
                        redisTemplate.expire(key, TTL_MINUTES, TimeUnit.MINUTES);
                    }
                } catch (Exception e) {
                    log.warn("Redis MSET 실패: {}", e.getMessage());
                }
            }
        }

        return resultMap;
    }

    // Redis 저장 객체를 User로 변환 (LinkedHashMap 문제 해결)
    private User convertToUser(Object cached) {
        if (cached instanceof User) {
            return (User) cached;
        }
        return objectMapper.convertValue(cached, User.class);
    }
}
