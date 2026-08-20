package interview.guide.modules.voiceinterview.session;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册表。
 *
 * <p>封装 ConcurrentHashMap&lt;String, WebSocketSession&gt;，统一处理会话注册（含消息大小
 * 限制与 {@link ConcurrentWebSocketSessionDecorator} 包装）、移除、查找与 open 判断。
 * 业务代码不再散落 {@code session != null && session.isOpen()}。</p>
 */
@Component
public class VoiceSessionManager {

    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    private static final int MAX_MESSAGE_SIZE = 256 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, WebSocketSession rawSession) {
        rawSession.setTextMessageSizeLimit(MAX_MESSAGE_SIZE);
        rawSession.setBinaryMessageSizeLimit(MAX_MESSAGE_SIZE);
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                rawSession, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES);
        sessions.put(sessionId, safeSession);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public boolean isConnected(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        return session != null && session.isOpen();
    }

    public WebSocketSession requireSession(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            throw new BusinessException(ErrorCode.VOICE_WS_SESSION_NOT_CONNECTED,
                    "语音面试 WebSocket 会话未连接: " + sessionId);
        }
        return session;
    }
}
