package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.pipeline.VoicePipeline;
import interview.guide.modules.voiceinterview.scheduler.VoiceInterviewTimeoutScheduler;
import interview.guide.modules.voiceinterview.sender.VoiceMessageSender;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.session.VoiceSessionManager;
import interview.guide.modules.voiceinterview.session.VoiceSessionStateManager;
import interview.guide.modules.voiceinterview.websocket.VoiceCommandRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket Handler for Voice Interview
 * 语音面试 WebSocket 处理器
 *
 * <p>只负责连接生命周期（建立、关闭、传输错误）与消息路由，业务编排下沉到
 * {@link VoicePipeline} 与 {@link VoiceCommandRouter}。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final VoiceSessionManager sessionManager;
    private final VoiceSessionStateManager stateManager;
    private final VoiceMessageSender sender;
    private final VoicePipeline pipeline;
    private final VoiceCommandRouter commandRouter;
    private final VoiceInterviewTimeoutScheduler timeoutScheduler;
    private final VoiceInterviewService interviewService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        sessionManager.register(sessionId, session);
        stateManager.getOrCreate(sessionId);
        timeoutScheduler.register(sessionId);
        log.info("WebSocket connection established for session: {}", sessionId);

        try {
            pipeline.onConnected(sessionId);
        } catch (Exception e) {
            log.error("Error establishing WebSocket connection for session {}", sessionId, e);
            sender.sendError(sessionId, "初始化语音识别失败: " + e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);

        try {
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();
            int messageSize = message.getPayload().length();
            int messageSizeKB = messageSize / 1024;
            if ("audio".equals(type)) {
                log.trace("[WebSocket] Received audio: sessionId={}, size={}KB", sessionId, messageSizeKB);
            } else {
                log.info("[WebSocket] Received message: sessionId={}, type={}, size={}KB ({} bytes)",
                    sessionId, type, messageSizeKB, messageSize);
            }

            if (messageSizeKB > 200) {
                log.warn("[WebSocket] Large message detected: {}KB", messageSizeKB);
            }

            timeoutScheduler.markActivity(sessionId);

            switch (type) {
                case "audio" -> {
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        pipeline.processAudio(sessionId, audioData);
                    } else {
                        log.warn("Received audio message without data");
                    }
                }
                case "control" -> {
                    try {
                        commandRouter.route(sessionId,
                            objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("Error handling control message for session {}", sessionId, e);
                        sender.sendError(sessionId, "控制消息处理失败: " + e.getMessage());
                    }
                }
                default -> log.warn("Unknown message type: {} for session {}", type, sessionId);
            }

        } catch (Exception e) {
            log.error("Error handling message for session {}", sessionId, e);
            sender.sendError(sessionId, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            sessionManager.remove(sessionId);
            pipeline.onDisconnected(sessionId);
            timeoutScheduler.unregister(sessionId);
            log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status);

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("Failed to auto-end session {} after disconnect: {}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("Error cleaning up session {} after close", sessionId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", extractSessionId(session), exception);
    }

    /**
     * Extract session ID from WebSocket URI path
     * Path format: /ws/voice-interview/{sessionId}
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
