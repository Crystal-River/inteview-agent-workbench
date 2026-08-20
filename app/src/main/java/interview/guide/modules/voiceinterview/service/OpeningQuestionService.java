package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.audio.AudioConverter;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.context.VoiceContextService;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.sender.VoiceMessageSender;
import interview.guide.modules.voiceinterview.session.VoiceSessionManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开场问题服务。
 *
 * <p>负责开场白模板选择、开场音频预热缓存、开场问题的文案落库与音频下发。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpeningQuestionService {

    private static final String DEFAULT_OPENING_QUESTION_ALGORITHM =
        "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲\u300C问题建模、数据结构选型、步骤、复杂度、边界处理\u300D。";
    private static final String DEFAULT_OPENING_QUESTION_BACKEND =
        "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";

    private final VoiceMessageSender sender;
    private final VoiceSessionManager sessionManager;
    private final QwenTtsService ttsService;
    private final VoiceContextService contextService;
    private final VoiceInterviewService interviewService;
    private final VoiceInterviewProperties voiceInterviewProperties;

    private final Map<String, byte[]> openingAudioCache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmupOpeningAudioCache() {
        if (!voiceInterviewProperties.isOpeningAudioWarmupEnabled()) {
            log.info("Opening audio cache warmup is disabled");
            return;
        }
        Thread.ofVirtual().name("opening-audio-warmup").start(() -> {
            try {
                VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
                if (opening == null) {
                    return;
                }
                LinkedHashSet<String> allTemplates = new LinkedHashSet<>();
                if (opening.getSkillQuestions() != null) {
                    allTemplates.addAll(opening.getSkillQuestions().values());
                }
                allTemplates.add(opening.getAlgorithmQuestion());
                allTemplates.add(opening.getBackendQuestion());
                for (String template : allTemplates) {
                    preloadOpeningAudio(template);
                }
                log.info("Opening audio cache warmed: {} entries", openingAudioCache.size());
            } catch (Exception e) {
                log.warn("Opening audio cache warmup skipped: {}", e.getMessage());
            }
        });
    }

    /**
     * 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）。
     * 由调用方决定在虚拟线程上执行。
     */
    public void triggerOpeningQuestionIfNeeded(String sessionId) {
        try {
            if (!sessionManager.isConnected(sessionId)) {
                return;
            }

            VoiceInterviewSessionEntity sessionEntity = interviewService.getSession(sessionId);
            if (sessionEntity == null) {
                log.warn("Session entity not found when sending opening question: {}", sessionId);
                return;
            }

            List<String> history = contextService.getHistory(sessionId, sessionEntity.getLlmProvider());
            if (history != null && !history.isEmpty()) {
                // 已有历史对话（如重连/恢复），不重复开场
                return;
            }

            String aiReply = buildOpeningQuestion(sessionEntity);
            if (aiReply == null || aiReply.isBlank()) {
                return;
            }

            if (!sessionManager.isConnected(sessionId)) {
                return;
            }

            // 先落库再推前端，确保用户提交时 DB 中已有该条消息
            persistMessage(sessionId, null, aiReply);

            // 语音随后下发
            byte[] wavAudio = getOpeningWavAudio(aiReply);
            if (wavAudio.length > 0 && sessionManager.isConnected(sessionId)) {
                sender.sendAudio(sessionId, wavAudio, aiReply);
            }

            sender.sendText(sessionId, aiReply, true);

            log.info("Opening question sent for session {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to send opening question for session {}", sessionId, e);
        }
    }

    private byte[] getOpeningWavAudio(String text) {
        byte[] cached = openingAudioCache.get(text);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] wav = synthesizeToWav(text);
        if (wav.length > 0) {
            openingAudioCache.put(text, wav);
        }
        return wav;
    }

    private byte[] synthesizeToWav(String text) {
        byte[] pcm = ttsService.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return AudioConverter.convertPcmToWav(pcm);
    }

    private void preloadOpeningAudio(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] wavAudio = synthesizeToWav(text);
        if (wavAudio.length > 0) {
            openingAudioCache.put(text, wavAudio);
        }
    }

    private void persistMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("Message saved to database for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error saving message for session {}", sessionId, e);
        }
    }

    private String buildOpeningQuestion(VoiceInterviewSessionEntity sessionEntity) {
        String skillId = sessionEntity.getSkillId() != null ? sessionEntity.getSkillId() : "";
        VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
        Map<String, String> skillQuestions = opening != null ? opening.getSkillQuestions() : null;
        if (skillQuestions != null) {
            String bySkill = skillQuestions.get(skillId);
            if (bySkill != null && !bySkill.isBlank()) {
                return bySkill;
            }
        }
        List<String> algorithmSkills = opening != null && opening.getAlgorithmSkills() != null
            ? opening.getAlgorithmSkills()
            : List.of();

        if (algorithmSkills.contains(skillId)) {
            String configured = opening != null ? opening.getAlgorithmQuestion() : null;
            return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_OPENING_QUESTION_ALGORITHM;
        }
        String configured = opening != null ? opening.getBackendQuestion() : null;
        return configured != null && !configured.isBlank()
            ? configured
            : DEFAULT_OPENING_QUESTION_BACKEND;
    }
}
