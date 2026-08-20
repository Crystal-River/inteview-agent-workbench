package interview.guide.modules.voiceinterview.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import interview.guide.modules.voiceinterview.session.VoiceSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.Map;

/**
 * WebSocket 消息发送器。
 *
 * <p>收敛所有下行消息（文本、字幕、音频、音频分块、控制、错误），统一处理
 * 会话缺失、连接关闭、JSON 序列化与异常捕获。业务代码不再直接调用
 * {@code session.sendMessage()}，也不需自行判断 {@code session.isOpen()}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceMessageSender {

    private final VoiceSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public void sendText(String sessionId, String content, boolean isFinal) {
        sendRaw(sessionId, toJson(Map.of(
                "type", "text",
                "content", content,
                "final", isFinal
        )));
    }

    public void sendSubtitle(String sessionId, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
                .type("subtitle")
                .text(text)
                .isFinal(isFinal)
                .build();
        sendRaw(sessionId, toJson(subtitle));
    }

    public void sendAudio(String sessionId, byte[] wavAudio, String text) {
        if (!sessionManager.isConnected(sessionId)) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        log.info("Sending audio to frontend - WAV size: {} bytes, Base64 length: {}",
                wavAudio.length, base64Audio.length());
        sendRaw(sessionId, toJson(Map.of(
                "type", "audio",
                "data", base64Audio,
                "text", text
        )));
    }

    public void sendAudioChunk(String sessionId, byte[] wavAudio, int index, boolean isLast) {
        if (!sessionManager.isConnected(sessionId)) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        sendRaw(sessionId, toJson(Map.of(
                "type", "audio_chunk",
                "data", base64Audio,
                "index", index,
                "isLast", isLast
        )));
        log.debug("[Session] Sent audio chunk index={}, isLast={}, size={} bytes",
                index, isLast, wavAudio.length);
    }

    public void sendAudioComplete(String sessionId) {
        sendControl(sessionId, "audio_complete", "面试官语音播放完成");
    }

    public void sendControl(String sessionId, String action, String message) {
        sendRaw(sessionId, toJson(Map.of(
                "type", "control",
                "action", action,
                "message", message,
                "timestamp", System.currentTimeMillis()
        )));
    }

    public void sendError(String sessionId, String error) {
        sendRaw(sessionId, toJson(Map.of("type", "error", "message", error)));
    }

    private void sendRaw(String sessionId, String json) {
        WebSocketSession session = sessionManager.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            log.debug("Session {} not connected, skip sending", sessionId);
            return;
        }
        try {
            session.sendMessage(new TextMessage(json));
            log.debug("Message sent to session: {}",
                    json.substring(0, Math.min(100, json.length())));
        } catch (Exception e) {
            log.error("Error sending message to session {}", sessionId, e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Error serializing JSON", e);
            return "{}";
        }
    }
}
