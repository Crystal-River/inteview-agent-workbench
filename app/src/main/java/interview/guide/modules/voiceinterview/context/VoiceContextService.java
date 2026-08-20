package interview.guide.modules.voiceinterview.context;

import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音面试对话历史上下文服务。
 *
 * <p>加载历史消息、执行上下文压缩（滑动窗口 + 增量摘要）、格式化为文本行，
 * 并持久化摘要行。上下文压缩与摘要持久化逻辑委托给 {@link VoiceContextCompressor}。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceContextService {

    private final VoiceInterviewService interviewService;
    private final VoiceContextCompressor voiceContextCompressor;

    /**
     * 获取会话的对话历史（压缩后格式化为文本行）。
     *
     * <p>当 {@code contextCompression.enabled=false} 时返回全量格式化轮次，与压缩引入前
     * 行为一致。</p>
     */
    public List<String> getHistory(String sessionId, String llmProvider) {
        try {
            List<VoiceInterviewMessageEntity> turns = interviewService.getConversationHistory(sessionId);
            VoiceInterviewMessageEntity summaryRow = interviewService.loadSummaryRow(sessionId).orElse(null);

            String cachedSummary = summaryRow != null
                ? VoiceInterviewMessageEntity.trimToNull(summaryRow.getAiGeneratedText()) : null;
            int coveredTurns = (summaryRow != null && summaryRow.getSequenceNum() != null)
                ? Math.max(0, -summaryRow.getSequenceNum() - 1) : 0;

            var compressed = voiceContextCompressor.compress(
                turns, cachedSummary, coveredTurns, llmProvider);

            List<String> history = new ArrayList<>();
            if (compressed.summary() != null && !compressed.summary().isBlank()) {
                history.add("【对话摘要】" + compressed.summary());
            }
            history.addAll(voiceContextCompressor.formatRecent(compressed.recent()));

            // 摘要发生变化则持久化（UPSERT），保证断线重连后不重复生成
            if (compressed.changed() && compressed.summary() != null && !compressed.summary().isBlank()) {
                try {
                    interviewService.saveSummaryRow(sessionId, compressed.summary(), compressed.coveredTurns());
                } catch (Exception e) {
                    log.warn("持久化上下文摘要失败（不影响本次应答），session {}", sessionId, e);
                }
            }

            log.debug("Loaded {} compressed history entries for session {}", history.size(), sessionId);
            return history;
        } catch (Exception e) {
            log.error("Error loading conversation history for session {}", sessionId, e);
            return new ArrayList<>();
        }
    }
}
