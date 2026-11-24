package com.erlyberly.web.config;

import com.erlyberly.web.node.SessionContextHolder;
import com.erlyberly.web.node.SessionManager;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 从请求中解析会话 id（?session= 优先，其次 X-Session-Id 头，缺省 default）。
 * 仅作用于 /api/**，WebSocket 由 WsBroker 自行解析。
 */
@Component
public class SessionContextFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sid = request.getParameter("session");
        if (sid == null || sid.isBlank()) {
            sid = request.getHeader("X-Session-Id");
        }
        SessionContextHolder.set(sid != null && !sid.isBlank() ? sid : SessionManager.DEFAULT_SESSION);
        try {
            chain.doFilter(request, response);
        } finally {
            SessionContextHolder.clear();
        }
    }
}
