package com.ktb.chatapp.websocket.socketio.aop;

import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.websocket.socketio.SocketUser;

@Aspect
@Component
public class SocketIOTraceAspect {

    @Pointcut("@annotation(com.corundumstudio.socketio.annotation.OnEvent)")
    public void onEventMethods() {
    }

    @Pointcut("@annotation(com.corundumstudio.socketio.annotation.OnConnect)")
    public void onConnectMethods() {
    }

    @Pointcut("@annotation(com.corundumstudio.socketio.annotation.OnDisconnect)")
    public void onDisconnectMethods() {
    }

    @Around("onEventMethods() || onConnectMethods() || onDisconnectMethods()")
    public Object traceSocketEvent(ProceedingJoinPoint joinPoint) throws Throwable {
        String traceId = UUID.randomUUID().toString();
        String apiPath = "socket/" + joinPoint.getSignature().getName(); // Default apiPath

        // Try to extract more specific info if possible
        // For example, if it's an event handler, we might want the event name, but
        // grabbing annotation value via reflection is costly maybe?
        // Let's stick to method name or simple prefix for now.

        // If arguments contain SocketIOClient, we might be able to get userId if
        // needed,
        // but for now let's just ensure MDC has traceId and apiPath.

        MDC.put("traceId", traceId);
        MDC.put("apiPath", apiPath);

        try {
            return joinPoint.proceed();
        } finally {
            MDC.remove("traceId");
            MDC.remove("apiPath");
        }
    }
}
