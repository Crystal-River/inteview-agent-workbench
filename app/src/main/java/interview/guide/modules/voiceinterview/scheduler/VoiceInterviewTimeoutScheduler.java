package interview.guide.modules.voiceinterview.scheduler;

import interview.guide.modules.voiceinterview.sender.VoiceMessageSender;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.session.VoiceSessionManager;
import interview.guide.modules.voiceinterview.session.VoiceSessionStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音面试超时与清理定时任务。
 *
 * <p>承接 checkPauseTimeout 与 cleanupStaleSessions 两个 {@code @Scheduled} 任务，
 * 以及暂停警告、暂停超时处理。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewTimeoutScheduler {

    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);  // 4:30
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;            // 5:00

    private final VoiceSessionManager sessionManager;
    private final VoiceSessionStateManager stateManager;
    private final VoiceMessageSender sender;
    private final QwenAsrService sttService;
    private final VoiceInterviewService interviewService;

    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();

    public void register(String sessionId) {
        lastActivityTime.put(sessionId, System.currentTimeMillis());
    }

    public void markActivity(String sessionId) {
        lastActivityTime.put(sessionId, System.currentTimeMillis());
    }

    public void unregister(String sessionId) {
        lastActivityTime.remove(sessionId);
    }

    /**
     * Scheduled task to check for pause warnings and timeouts
     * Runs every 30 seconds
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        lastActivityTime.forEach((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            // Send warning at 4:30
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            }
            // Timeout at 5:00
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("Session {} inactive for {} minutes, pausing",
                    sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupStaleSessions() {
        try {
            int cleaned = interviewService.cleanupStaleSessions();
            if (cleaned > 0) {
                log.info("Stale session cleanup: {} sessions cleaned", cleaned);
            }
        } catch (Exception e) {
            log.error("Error during stale session cleanup", e);
        }
    }

    /**
     * Send pause warning notification
     */
    private void sendPauseWarning(String sessionId) {
        if (sessionManager.isConnected(sessionId)) {
            sender.sendControl(sessionId, "pause_timeout_warning",
                "会话将在30秒后暂停，请继续说话或点击继续");
        }
    }

    /**
     * Handle pause timeout - save state and disconnect
     */
    private void handlePauseTimeout(String sessionId) {
        try {
            if (sessionManager.isConnected(sessionId)) {
                sender.sendControl(sessionId, "pause_timeout",
                    "会话因超时已暂停,可在历史记录中恢复");
            }

            // 2. Save session state to database
            interviewService.pauseSession(sessionId, "timeout");

            // 3. Close WebSocket connection
            WebSocketSession session = sessionManager.getSession(sessionId);
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            // 4. Cleanup - Stop ASR session to prevent resource leak
            sttService.stopTranscription(sessionId);
            sessionManager.remove(sessionId);
            stateManager.remove(sessionId);
            unregister(sessionId);

            log.info("Session {} paused due to timeout", sessionId);

        } catch (Exception e) {
            log.error("Error handling pause timeout for session {}", sessionId, e);
        }
    }
}
