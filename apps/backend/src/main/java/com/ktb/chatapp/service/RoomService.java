package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final UserCacheService userCacheService; // Redis Cache Service
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest, String name) {

        try {
            // 정렬 설정 검증
            if (!pageRequest.isValidSortField()) {
                pageRequest.setSortField("createdAt");
            }
            if (!pageRequest.isValidSortOrder()) {
                pageRequest.setSortOrder("desc");
            }

            // 정렬 방향 설정
            Sort.Direction direction = "desc".equals(pageRequest.getSortOrder())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;

            // 정렬 필드 매핑 (participantsCount는 특별 처리 필요)
            String sortField = pageRequest.getSortField();
            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds"; // MongoDB 필드명으로 변경
            }

            // Pageable 객체 생성
            PageRequest springPageRequest = PageRequest.of(
                    pageRequest.getPage(),
                    pageRequest.getPageSize(),
                    Sort.by(direction, sortField));

            // 검색어가 있는 경우와 없는 경우 분리
            Page<Room> roomPage;
            if (pageRequest.getSearch() != null && !pageRequest.getSearch().trim().isEmpty()) {
                roomPage = roomRepository.findByNameContainingIgnoreCase(
                        pageRequest.getSearch().trim(), springPageRequest);
            } else {
                roomPage = roomRepository.findAll(springPageRequest);
            }

            List<Room> rooms = roomPage.getContent();
            List<String> roomIds = rooms.stream().map(Room::getId).toList();

            // 1. 모든 관련 User ID 수집 (Creator + Participants)
            Set<String> userIds = new HashSet<>();
            for (Room room : rooms) {
                if (room.getCreator() != null) {
                    userIds.add(room.getCreator());
                }
                if (room.getParticipantIds() != null) {
                    userIds.addAll(room.getParticipantIds());
                }
            }

            // 2. User 일괄 조회 (Redis Cache + DB Batch)
            Map<String, User> userMap = userCacheService.getUsers(userIds);

            // 3. 최근 메시지 수 일괄 조회 (Aggregation)
            LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
            Map<String, Long> messageCounts = new HashMap<>();
            try {
                List<MessageCountResult> results = messageRepository.countRecentMessagesByRoomIds(roomIds,
                        tenMinutesAgo);
                for (MessageCountResult result : results) {
                    messageCounts.put(result.getRoomId(), result.getCount());
                }
            } catch (Exception e) {
                log.error("Failed to count messages batch", e);
            }

            // Room을 RoomResponse로 변환 (UserMap, MessageCounts 활용)
            List<RoomResponse> roomResponses = rooms.stream()
                    .map(room -> convertToRoomResponse(room, name, userMap,
                            messageCounts.getOrDefault(room.getId(), 0L)))
                    .collect(Collectors.toList());

            // 메타데이터 생성
            PageMetadata metadata = PageMetadata.builder()
                    .total(roomPage.getTotalElements())
                    .page(pageRequest.getPage())
                    .pageSize(pageRequest.getPageSize())
                    .totalPages(roomPage.getTotalPages())
                    .hasMore(roomPage.hasNext())
                    .currentCount(roomResponses.size())
                    .sort(PageMetadata.SortInfo.builder()
                            .field(pageRequest.getSortField())
                            .order(pageRequest.getSortOrder())
                            .build())
                    .build();

            return RoomsResponse.builder()
                    .success(true)
                    .data(roomResponses)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                    .success(false)
                    .data(List.of())
                    .build();
        }
    }

    private RoomResponse convertToRoomResponse(Room room, String currentUserName, Map<String, User> userMap,
            long recentMessageCount) {
        User creator = room.getCreator() != null ? userMap.get(room.getCreator()) : null;

        List<UserResponse> participants = room.getParticipantIds().stream()
                .map(userMap::get)
                .filter(java.util.Objects::nonNull)
                .map(p -> UserResponse.builder()
                        .id(p.getId())
                        .name(p.getName() != null ? p.getName() : "알 수 없음")
                        .email(p.getEmail() != null ? p.getEmail() : "")
                        .build())
                .toList();

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.builder()
                        .id(creator.getId())
                        .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                        .email(creator.getEmail() != null ? creator.getEmail() : "")
                        .build() : null)
                .participants(participants)
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(creator != null && creator.getId().equals(currentUserName))
                .recentMessageCount((int) recentMessageCount)
                .build();
    }

    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();

            // MongoDB 연결 상태 확인
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                // 간단한 쿼리로 연결 상태 및 지연 시간 측정
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            // 최근 활동 조회
            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            // 서비스 상태 정보 구성
            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                    .connected(isMongoConnected)
                    .latency(latency)
                    .build());

            return HealthResponse.builder()
                    .success(true)
                    .services(services)
                    .lastActivity(lastActivity)
                    .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                    .success(false)
                    .services(new HashMap<>())
                    .build();
        }
    }

    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);

        // Publish event for room created
        try {
            RoomResponse roomResponse = mapToRoomResponse(savedRoom, name);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return savedRoom;
    }

    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        // 비밀번호 확인
        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        // 이미 참여중인지 확인
        if (!room.getParticipantIds().contains(user.getId())) {
            // 채팅방 참여
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }

        // Publish event for room updated
        try {
            RoomResponse roomResponse = mapToRoomResponse(room, name);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }

    // 기존 단건 조회용
    private RoomResponse mapToRoomResponse(Room room, String name) {
        if (room == null)
            return null;

        User creator = null;
        if (room.getCreator() != null) {
            // 여기서도 캐시 사용하면 좋음
            creator = userCacheService.getUser(room.getCreator());
        }

        // 단건 방 조회 시 참가자도 캐시로 조회 (Batch 아님) - 사실 여기도 Batch로 바꾸면 더 좋음
        // 하지만 RoomCreate 등에서 호출될 때 참가자는 보통 생성자 1명이므로 문제 없음
        // joinRoom에서는 참가자가 많을 수 있으므로... userCacheService.getUsers()로 바꿔주는게 안전함.

        Set<String> pIds = room.getParticipantIds() != null ? room.getParticipantIds() : Collections.emptySet();
        Map<String, User> userMap = userCacheService.getUsers(pIds);

        if (creator != null) {
            // 중복 방지 로직 필요 없지만 map에 있으면 덮어쓰기
            // userCacheService.getUsers는 Set 결과이므로 creator 포함 안될수있음 (creator가 participant에
            // 없다면)
            // 보통 creator는 participant에 포함됨.
        }

        return convertToRoomResponse(room, name, userMap,
                messageRepository.countRecentMessagesByRoomId(room.getId(), LocalDateTime.now().minusMinutes(10)));
    }
}
