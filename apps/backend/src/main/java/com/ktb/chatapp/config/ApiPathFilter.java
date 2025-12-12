package com.ktb.chatapp.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiPathFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        String uri = request.getRequestURI();
        String method = request.getMethod(); // GET, POST, PUT, DELETE, etc.

        String normalizedUri = uri.replaceAll("/rooms/([a-zA-Z0-9]+)$", "/rooms/{id}")
                .replaceAll("/rooms/([a-zA-Z0-9]+)/join", "/rooms/{id}/join")
                .replaceAll("/files/view/([a-zA-Z0-9]+).*$", "/files/view/{id}");

        // Combine Method and URI, e.g., "GET /api/rooms/{id}"
        MDC.put("apiPath", method + " " + normalizedUri);

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("apiPath");
        }
    }
}
