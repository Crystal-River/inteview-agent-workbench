package interview.guide.modules.voiceinterview.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态注册表。
 *
 * <p>封装 ConcurrentHashMap&lt;String, SessionState&gt;，负责 SessionState 的
 * 创建、查找与移除。</p>
 */
@Component
public class VoiceSessionStateManager {

    private final Map<String, SessionState> states = new ConcurrentHashMap<>();

    public SessionState getOrCreate(String sessionId) {
        return states.computeIfAbsent(sessionId, key -> new SessionState());
    }

    public SessionState get(String sessionId) {
        return states.get(sessionId);
    }

    public SessionState remove(String sessionId) {
        return states.remove(sessionId);
    }
}
