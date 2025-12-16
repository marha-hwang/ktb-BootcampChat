package com.ktb.chatapp.websocket.socketio;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class UserRooms {

    private static final String USER_ROOM_KEY_PREFIX = "userroom:roomids:";

    private final RedissonClient redissonClient;

    private RSet<String> getRoomSet(String userId) {
        return redissonClient.getSet(buildKey(userId), StringCodec.INSTANCE);
    }

    /**
     * Get all room IDs for a user
     *
     * @param userId the user ID
     * @return the set of room IDs the user is currently in, or empty set if not in any room
     */
    public Set<String> get(String userId) {
        return getRoomSet(userId).readAll();
    }

    /**
     * Add a room ID for a user
     *
     * @param userId the user ID
     * @param roomId the room ID to add to the user's room set
     */
    public void add(String userId, String roomId) {
        getRoomSet(userId).add(roomId);
    }

    /**
     * Remove a specific room ID from a user's room set
     *
     * @param userId the user ID
     * @param roomId the room ID to remove
     */
    public void remove(String userId, String roomId) {
        RSet<String> rooms = getRoomSet(userId);
        rooms.remove(roomId);
        if (rooms.isEmpty()) {
            rooms.delete();
        }
    }

    /**
     * Remove all room associations for a user
     *
     * @param userId the user ID
     */
    public void clear(String userId) {
        getRoomSet(userId).delete();
    }

    /**
     * Check if a user is in a specific room
     *
     * @param userId the user ID
     * @param roomId the room ID to check
     * @return true if the user is in the room, false otherwise
     */
    public boolean isInRoom(String userId, String roomId) {
        return getRoomSet(userId).contains(roomId);
    }

    private String buildKey(String userId) {
        return USER_ROOM_KEY_PREFIX + userId;
    }
    
    public void removeAllRooms(String userId) {
        clear(userId);
    }
}
