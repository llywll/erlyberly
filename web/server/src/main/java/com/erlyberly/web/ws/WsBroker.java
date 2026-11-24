package com.erlyberly.web.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话路由的 WebSocket 推送：连接建立时从 /ws?session=xxx 读取会话 id，
 * NodeSession 的事件只推给订阅了该会话的连接（支持多开）。
 */
@Component
public class WsBroker extends TextWebSocketHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<WebSocketSession, String> sessionNodeMap = new ConcurrentHashMap<>();

    /** 客户端 → 会话 的输入路由（由 SessionManager 注册：把 shell_in 写入对应 NodeSession 的 pty） */
    private volatile java.util.function.BiConsumer<String, String> shellInputRouter;

    public void setShellInputRouter(java.util.function.BiConsumer<String, String> router) {
        this.shellInputRouter = router;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionNodeMap.put(session, nodeSessionIdOf(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端 → 服务端：{type: "shell_in", data: "按键字节"}
        try {
            var node = MAPPER.readTree(message.getPayload());
            String type = node.path("type").asText();
            if ("shell_in".equals(type) && shellInputRouter != null) {
                String sid = sessionNodeMap.get(session);
                if (sid != null) {
                    shellInputRouter.accept(sid, node.path("data").asText());
                }
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessionNodeMap.remove(session);
    }

    public static String nodeSessionIdOf(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            for (String kv : uri.getQuery().split("&")) {
                if (kv.startsWith("session=")) {
                    return kv.substring("session=".length());
                }
            }
        }
        return "default";
    }

    /** 向指定 node 会话的所有订阅连接推送事件 */
    public void send(String nodeSessionId, String type, Object data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("data", data);
        String json;
        try {
            json = MAPPER.writeValueAsString(msg);
        } catch (IOException e) {
            return;
        }
        TextMessage tm = new TextMessage(json);
        sessionNodeMap.forEach((ws, sid) -> {
            if (sid.equals(nodeSessionId) && ws.isOpen()) {
                try {
                    synchronized (ws) {
                        ws.sendMessage(tm);
                    }
                } catch (IOException | IllegalStateException e) {
                    sessionNodeMap.remove(ws);
                }
            }
        });
    }
}
