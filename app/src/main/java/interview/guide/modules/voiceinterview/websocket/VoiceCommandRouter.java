package interview.guide.modules.voiceinterview.websocket;

import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.pipeline.VoicePipeline;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 语音面试 WebSocket 控制消息路由器。
 *
 * <p>承接原 Handler 的 handleControl 逻辑，分发 submit / end_interview / start_phase。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceCommandRouter {

    private final VoicePipeline pipeline;
    private final VoiceInterviewService interviewService;

    public void route(String sessionId, WebSocketControlMessage control) {
        log.info("Control message for session {}: action={}, phase={}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "submit" -> pipeline.submit(sessionId, extractSubmitText(control));
            case "end_interview" -> interviewService.endSession(sessionId);
            case "start_phase" -> interviewService.startPhase(sessionId, control.getPhase());
            default -> log.warn("Unknown control action: {} for session {}",
                    control.getAction(), sessionId);
        }
    }

    private String extractSubmitText(WebSocketControlMessage control) {
        if (control.getData() == null) {
            return null;
        }
        Object textObj = control.getData().get("text");
        if (textObj instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}
