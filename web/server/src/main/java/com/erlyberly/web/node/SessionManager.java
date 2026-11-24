package com.erlyberly.web.node;

import com.erlyberly.web.ws.WsBroker;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接会话管理：每个 sessionId 对应一个独立的 NodeSession（独立 NodeAPI 连接），
 * 支持多开——不同浏览器标签各自连接不同节点互不干扰。
 */
@Service
public class SessionManager {

    public static final String DEFAULT_SESSION = "default";

    private final WsBroker wsBroker;
    private final Map<String, NodeSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(WsBroker wsBroker) {
        this.wsBroker = wsBroker;
    }

    public NodeSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public synchronized NodeSession getOrCreate(String sessionId) {
        NodeSession created = sessions.computeIfAbsent(sessionId, id ->
                new NodeSession(id, (type, data) -> wsBroker.send(id, type, data)));
        // 注册 WS shell_in 输入路由（幂等，把浏览器按键写入对应会话的 pty）
        wsBroker.setShellInputRouter((sid, data) -> {
            try {
                NodeSession s = sessions.get(sid);
                if (s != null) {
                    s.shellWrite(data);
                }
            } catch (Exception ignored) {
            }
        });
        return created;
    }

    /** 关闭并移除会话 */
    public synchronized void remove(String sessionId) {
        NodeSession s = sessions.remove(sessionId);
        if (s != null) {
            s.shutdown();
        }
    }

    /** JVM 优雅退出时停掉所有会话（否则 remsh 子进程会变孤儿，残挂在目标节点上） */
    @jakarta.annotation.PreDestroy
    public synchronized void shutdownAll() {
        sessions.values().forEach(NodeSession::shutdown);
        sessions.clear();
    }

    public Map<String, NodeSession> all() {
        return sessions;
    }
}
