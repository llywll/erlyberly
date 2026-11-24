package com.erlyberly.web.node;

/**
 * 请求级会话上下文：SessionContextFilter 从 ?session= 或 X-Session-Id 头解析出
 * 当前请求所属的连接会话，ApiController 通过 current() 取用。
 */
public final class SessionContextHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SessionContextHolder() {
    }

    public static void set(String sessionId) {
        CURRENT.set(sessionId);
    }

    public static String current() {
        String sid = CURRENT.get();
        return sid != null ? sid : SessionManager.DEFAULT_SESSION;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
