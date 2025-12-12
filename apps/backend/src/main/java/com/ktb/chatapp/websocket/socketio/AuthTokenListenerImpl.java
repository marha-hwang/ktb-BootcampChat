package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.JwtService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.websocket.socketio.handler.ConnectionLoginHandler;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Socket.IO Authorization Handler
 * socket.handshake.auth.token과 sessionId를 처리한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuthTokenListenerImpl implements AuthTokenListener {

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final ObjectProvider<ConnectionLoginHandler> socketIOChatHandlerProvider;

    @Override
    public AuthTokenResult getAuthTokenResult(Object _authToken, SocketIOClient client) {
        // AOP가 잡지 못하는 메서드이므로 직접 MDC 컨텍스트 설정
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        MDC.put("apiPath", "socket/connect");

        try {
            var authToken = (Map<?, ?>) _authToken;
            if (authToken == null) {
                return new AuthTokenResult(false, Map.of("message", "Authentication required"));
            }

            String token = authToken.get("token") != null ? authToken.get("token").toString() : null;
            String sessionId = authToken.get("sessionId") != null ? authToken.get("sessionId").toString() : null;

            if (token == null || sessionId == null) {
                log.warn("Missing authentication credentials in Socket.IO handshake - token: {}, sessionId: {}",
                        token != null, sessionId != null);
                return new AuthTokenResult(false, "Authentication error");
            }

            String userId;
            try {
                userId = jwtService.extractUserId(token);
            } catch (JwtException e) {
                return new AuthTokenResult(false, Map.of("message", "Invalid token"));
            }

            // Validate session using SessionService
            SessionValidationResult validationResult = sessionService.validateSession(userId, sessionId);

            if (!validationResult.isValid()) {
                log.error("Session validation failed: {}", validationResult.getMessage());
                return new AuthTokenResult(false, Map.of("message", "Invalid session"));
            }

            // Load user from database (이부분이 DB 조회)
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.error("User not found: {}", userId);
                return new AuthTokenResult(false, Map.of("message", "User not found"));
            }

            log.info("Socket.IO connection authorized for user: {} ({})", user.getName(), userId);

            var socketUser = new SocketUser(user.getId(), user.getName(), sessionId, client.getSessionId().toString());
            socketIOChatHandlerProvider.getObject().onConnect(client, socketUser);
            return AuthTokenResult.AuthTokenResultSuccess;
        } catch (Exception e) {
            log.error("Socket.IO authentication error: {}", e.getMessage(), e);
            return new AuthTokenResult(false, Map.of("message", e.getMessage()));
        } finally {
            MDC.remove("traceId");
            MDC.remove("apiPath");
        }
    }
}
