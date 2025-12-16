package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncMessagingService {

    private final MessageRepository messageRepository;

    /**
     * 비동기로 메시지 저장
     * Fire-and-Forget 패턴: 저장 성공 여부를 호출자가 기다리지 않음
     */
    @Async
    public void saveMessage(Message message, Map<String, String> contextMap) {
        // 비동기 스레드에 MDC 컨텍스트 복사
        if (contextMap != null) {
            MDC.setContextMap(contextMap);
        }

        try {
            long start = System.currentTimeMillis();
            messageRepository.save(message);
            long end = System.currentTimeMillis();

            log.debug("Async message saved. id: {}, duration: {}ms", message.getId(), (end - start));
        } catch (Exception e) {
            log.error("Failed to save message asynchronously. id: {}, content: {}", message.getId(),
                    message.getContent(), e);
            // 여기서 실패하면 재시도 로직이나 DLQ(Dead Letter Queue)로 보내야 하지만,
            // 현재는 로그만 남김 (Eventual Consistency)
        } finally {
            MDC.clear();
        }
    }
}
