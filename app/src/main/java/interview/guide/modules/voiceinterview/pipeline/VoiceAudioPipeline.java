package interview.guide.modules.voiceinterview.pipeline;

import interview.guide.modules.voiceinterview.audio.AudioConverter;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.context.VoiceContextService;
import interview.guide.modules.voiceinterview.metrics.VoiceInterviewMetrics;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.sender.VoiceMessageSender;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.OpeningQuestionService;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.session.SessionState;
import interview.guide.modules.voiceinterview.session.VoiceSessionManager;
import interview.guide.modules.voiceinterview.session.VoiceSessionStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音面试实时链路核心编排实现。
 *
 * <p>承接 ASR 连接管理、音频上行、STT 结果合并、LLM 流式响应、句子级并发 TTS
 * 与音频回推的全链路，并持有虚拟线程池与合并调度器。所有下行消息经
 * {@link VoiceMessageSender} 统一发送，会话连接状态经 {@link VoiceSessionManager} 统一判断。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceAudioPipeline implements VoicePipeline, DisposableBean {

    /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
    private static final long AI_SPEAK_COOLDOWN_MS = 800;
    private static final int MAX_ASR_READY_RETRY = 2;
    private static final long ASR_READY_CHECK_DELAY_SECONDS = 10;

    private final QwenAsrService sttService;
    private final QwenTtsService ttsService;
    private final DashscopeLlmService llmService;
    private final VoiceInterviewService interviewService;
    private final VoiceSessionManager sessionManager;
    private final VoiceSessionStateManager stateManager;
    private final VoiceMessageSender sender;
    private final VoiceContextService contextService;
    private final OpeningQuestionService openingQuestionService;
    private final VoiceInterviewMetrics metrics;
    private final VoiceInterviewProperties voiceInterviewProperties;

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度。
     */
    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    /**
     * LLM / TTS / JDBC 等阻塞工作全部跑在虚拟线程上，避免占满 utteranceMergeScheduler 的调度线程。
     */
    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void onConnected(String sessionId) {
        startDashScopeStt(sessionId);
        sender.sendControl(sessionId, "welcome", "连接成功，准备开始语音面试");
        // 自动开场包含阻塞的 DB / TTS 调用，放到虚拟线程异步执行，不阻塞连接建立
        voicePipelineExecutor.execute(() -> openingQuestionService.triggerOpeningQuestionIfNeeded(sessionId));
    }

    @Override
    public void onDisconnected(String sessionId) {
        SessionState state = stateManager.remove(sessionId);
        if (state != null) {
            Thread t = state.getProcessingThread();
            if (t != null) {
                t.interrupt();
            }
        }
        sttService.stopTranscription(sessionId);
        log.info("Voice pipeline stopped for session {}", sessionId);
    }

    @Override
    public void processAudio(String sessionId, String base64Audio) {
        if (!sessionManager.isConnected(sessionId)) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        // AI 正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发 LLM
        SessionState state = stateManager.get(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            return;
        }

        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("Received audio data for session {}, size: {} bytes", sessionId, audioData.length);

            try {
                sttService.sendAudio(sessionId, audioData);
            } catch (IllegalStateException ex) {
                if (isAsrNotReady(ex)) {
                    log.debug("[Session: {}] Dropping audio chunk before ASR ready", sessionId);
                    return;
                } else if (shouldRecoverAsrConnection(ex)) {
                    log.warn("[Session: {}] ASR send failed ({}), restarting DashScope and retrying chunk",
                            sessionId, ex.getMessage() != null ? ex.getMessage() : "unknown");
                    restartDashScopeStt(sessionId);
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;
                        } catch (IllegalStateException retry) {
                            if (isAsrNotReady(retry)) {
                                continue;
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[Session: {}] ASR still down after restart", sessionId);
                        sender.sendError(sessionId, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    throw ex;
                }
            }

        } catch (Exception e) {
            log.error("Error handling user audio for session {}", sessionId, e);
            sender.sendError(sessionId, getErrorMessage(e));
        }
    }

    @Override
    public void submit(String sessionId, String overrideText) {
        if (overrideText != null && !overrideText.isBlank()) {
            SessionState state = stateManager.get(sessionId);
            if (state != null) {
                state.setMergeBufferDirectly(overrideText);
            }
        }
        flushMergedUtteranceToLlm(sessionId);
    }

    private void startDashScopeStt(String sessionId) {
        sttService.startTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> sender.sendControl(sessionId, "asr_ready", "语音识别已就绪"),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sender.sendError(sessionId, "语音识别失败: " + error.getMessage());
                }
        );

        scheduleAsrReadyCheck(sessionId, 0);
    }

    private void scheduleAsrReadyCheck(String sessionId, int retryCount) {
        utteranceMergeScheduler.schedule(
            () -> checkAsrReadyOrRetry(sessionId, retryCount),
            ASR_READY_CHECK_DELAY_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void checkAsrReadyOrRetry(String sessionId, int retryCount) {
        if (!sessionManager.isConnected(sessionId) || sttService.isReady(sessionId)) {
            return;
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            int nextRetry = retryCount + 1;
            log.warn("[Session: {}] ASR not ready after {}s, retrying ({}/{})",
                sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY);
            sender.sendControl(sessionId, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            restartDashScopeStt(sessionId);
            scheduleAsrReadyCheck(sessionId, nextRetry);
            return;
        }

        log.warn("[Session: {}] ASR still not ready after {} retries", sessionId, retryCount);
        sender.sendError(sessionId, "语音识别连接准备超时，请检查语音服务配置或稍后重试");
    }

    /**
     * DashScope ASR 断线后重连（回调与首次 start 一致）。
     */
    private void restartDashScopeStt(String sessionId) {
        if (!sessionManager.isConnected(sessionId)) {
            return;
        }
        sttService.restartTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> sender.sendControl(sessionId, "asr_ready", "语音识别已就绪"),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sender.sendError(sessionId, "语音识别失败: " + error.getMessage());
                }
        );
    }

    /**
     * Handle STT result from callback (partial = live; final = committed segment for LLM).
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        SessionState state = stateManager.get(sessionId);

        if (state == null) {
            log.warn("Session state not found: {}", sessionId);
            return;
        }

        if (!isFinalSegment) {
            state.markSttActivity();
            sender.sendSubtitle(sessionId, state.getMergeBufferPreviewWithPartial(recognizedText), false);
            return;
        }

        // 用户已提交、LLM 正在处理时，丢弃迟到的 STT 定稿段，防止污染下一轮 mergeBuffer
        if (state.isProcessing().get()) {
            log.debug("Discarding late STT final segment during processing for session {}: {}",
                sessionId, recognizedText);
            return;
        }

        log.debug("STT final segment for session {}: {}", sessionId, recognizedText);
        metrics.incrementCounter("app.voice.interview.asr.final_segments", "status", "received");

        // 合并多次 VAD 切段，只更新实时字幕；是否提交给 LLM 由前端手动 submit 控制
        state.appendFinalSttSegment(recognizedText);
        sender.sendSubtitle(sessionId, state.getMergeBufferPreview(), false);
    }

    /**
     * 手动提交：获取 mergeBuffer 中累积的用户文本并触发 LLM 管线。
     */
    private void flushMergedUtteranceToLlm(String sessionId) {
        SessionState state = stateManager.get(sessionId);
        if (state == null || !sessionManager.isConnected(sessionId)) {
            return;
        }
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        metrics.recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success");
        state.setAccumulatedText(userText);
        log.info("Merged user utterance for session {}, triggering LLM (length {})", sessionId, userText.length());

        // 提交到虚拟线程执行阻塞的 LLM+TTS 管线，立即释放调度器线程
        voicePipelineExecutor.execute(() -> {
            state.setProcessingThread(Thread.currentThread());
            try {
                triggerLlmResponse(sessionId, state);
            } finally {
                state.isProcessing().set(false);
                state.setProcessingThread(null);
            }
        });
    }

    /**
     * Trigger LLM response for completed sentence.
     * When streaming is enabled, uses sentence-level TTS overlap: each detected sentence
     * triggers a concurrent TTS call, so TTS runs in parallel with the rest of LLM generation.
     */
    private void triggerLlmResponse(String sessionId, SessionState state) {
        long turnStartNanos = System.nanoTime();
        state.startAiSpeaking();
        try {
            if (!sessionManager.isConnected(sessionId)) {
                log.warn("WebSocket session is closed, skipping LLM response for session {}", sessionId);
                return;
            }

            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("Empty user text, skipping LLM response");
                return;
            }

            log.info("Getting LLM response for session {}, text: {}", sessionId, userText);

            VoiceInterviewSessionEntity sessionEntity = interviewService.getSession(sessionId);
            if (sessionEntity == null) {
                log.error("Session entity not found for session {}, cannot generate LLM response", sessionId);
                sender.sendError(sessionId, "会话不存在，请重新开始面试");
                return;
            }

            List<String> conversationHistory = contextService.getHistory(sessionId, sessionEntity.getLlmProvider());

            long llmStartNanos = System.nanoTime();
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();
            String aiReply;

            if (streamEnabled) {
                // 句子级并发 TTS：LLM 流式输出期间每检测到一个完整句子就启动 TTS
                Semaphore ttsSemaphore = new Semaphore(
                    Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
                boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();
                long ttsTimeoutSec = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
                OrderedTtsChunkEmitter chunkEmitter = chunkedEnabled
                    ? new OrderedTtsChunkEmitter(sessionId, ttsSemaphore, ttsTimeoutSec)
                    : null;
                List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

                aiReply = llmService.chatStreamSentences(
                    userText,
                    partialText -> {
                        if (partialText == null || partialText.isBlank() || !sessionManager.isConnected(sessionId)) {
                            return;
                        }
                        if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                            metrics.recordTimerSinceNanos(
                                "app.voice.interview.llm.first_token_latency",
                                llmStartNanos,
                                "status", "success"
                            );
                        }
                        sender.sendText(sessionId, partialText, false);
                    },
                    sentence -> {
                        if (sentence == null || sentence.isBlank()) {
                            return;
                        }
                        if (chunkEmitter != null) {
                            chunkEmitter.submit(sentence);
                            return;
                        }
                        ttsSemaphore.acquireUninterruptibly();
                        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                return ttsService.synthesize(sentence);
                            } finally {
                                ttsSemaphore.release();
                            }
                        }, voicePipelineExecutor);
                        ttsFutures.add(future);
                    },
                    sessionEntity,
                    conversationHistory
                );

                metrics.recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                metrics.incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!sessionManager.isConnected(sessionId)) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                sender.sendSubtitle(sessionId, userText, true);
                sender.sendText(sessionId, aiReply, true);
                persistMessage(sessionId, userText, aiReply);

                // 按顺序收集所有 TTS 结果（带超时，防止单句 TTS 挂死阻塞整条管道）
                if (chunkEmitter != null) {
                    long ttsStartNanos = System.nanoTime();
                    chunkEmitter.finish();
                    int emittedChunks = chunkEmitter.awaitCompletion();
                    metrics.recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");
                    if (emittedChunks == 0 && sessionManager.isConnected(sessionId)) {
                        log.info("[Session: {}] Streaming TTS produced no chunks, falling back to full-text TTS",
                            sessionId);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                sender.sendAudio(sessionId, AudioConverter.convertPcmToWav(fallbackPcm), aiReply);
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS failed: {}", sessionId, e.getMessage());
                        }
                    }
                } else if (!ttsFutures.isEmpty()) {
                    long ttsStartNanos = System.nanoTime();
                    // 合并模式：收集所有 PCM 后合并为一个完整音频
                    List<byte[]> pcmChunks = new ArrayList<>();
                    int totalSize = 0;
                    int failedCount = 0;
                    boolean audioSentByFallback = false;
                    for (CompletableFuture<byte[]> f : ttsFutures) {
                        try {
                            byte[] pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS);
                            if (pcm != null && pcm.length > 0) {
                                pcmChunks.add(pcm);
                                totalSize += pcm.length;
                            }
                        } catch (Exception e) {
                            f.cancel(true);
                            failedCount++;
                            log.warn("[Session: {}] TTS future failed for one sentence: {}", sessionId, e.getMessage());
                        }
                    }
                    metrics.recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                    if (!sessionManager.isConnected(sessionId)) {
                        log.warn("WebSocket closed during TTS processing, discarding audio for session {}", sessionId);
                        return;
                    }

                    // 有句子级 TTS 失败且无成功结果时，用完整文本做一次兜底 TTS
                    if (totalSize == 0 && failedCount > 0 && sessionManager.isConnected(sessionId)) {
                        log.info("[Session: {}] All {} sentence TTS calls failed, falling back to full-text TTS",
                            sessionId, failedCount);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                byte[] wavAudio = AudioConverter.convertPcmToWav(fallbackPcm);
                                log.info("[Session: {}] Fallback TTS succeeded, WAV size: {} bytes",
                                    sessionId, wavAudio.length);
                                sender.sendAudio(sessionId, wavAudio, aiReply);
                                audioSentByFallback = true;
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS also failed: {}", sessionId, e.getMessage());
                        }
                    }

                    if (!audioSentByFallback) {
                        if (totalSize > 0 && sessionManager.isConnected(sessionId)) {
                            byte[] mergedPcm = new byte[totalSize];
                            int offset = 0;
                            for (byte[] chunk : pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                                offset += chunk.length;
                            }
                            byte[] wavAudio = AudioConverter.convertPcmToWav(mergedPcm);
                            log.info("[Session: {}] Sending merged audio - {} sentences, WAV size: {} bytes",
                                sessionId, pcmChunks.size(), wavAudio.length);
                            sender.sendAudio(sessionId, wavAudio, aiReply);
                        } else {
                            log.error("[Session: {}] All TTS calls returned empty audio", sessionId);
                            metrics.incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                        }
                    }
                }
            } else {
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                metrics.recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                metrics.incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!sessionManager.isConnected(sessionId)) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                sender.sendSubtitle(sessionId, userText, true);
                sender.sendText(sessionId, aiReply, true);
                persistMessage(sessionId, userText, aiReply);

                long ttsStartNanos = System.nanoTime();
                log.info("[Session: {}] Starting TTS synthesis for text (length: {})",
                    sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply);
                metrics.recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                if (!sessionManager.isConnected(sessionId)) {
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[Session: {}] TTS returned empty audio", sessionId);
                    metrics.incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                } else {
                    byte[] wavAudio = AudioConverter.convertPcmToWav(aiAudio);
                    sender.sendAudio(sessionId, wavAudio, aiReply);
                }
            }

            state.setAccumulatedText("");
            metrics.recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success");
            metrics.incrementCounter("app.voice.interview.turn.completed", "status", "success");

        } catch (Exception e) {
            log.error("Error triggering LLM response for session {}", sessionId, e);
            metrics.recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure");
            metrics.incrementCounter("app.voice.interview.turn.completed", "status", "failure");
            metrics.incrementCounter("app.voice.interview.errors", "stage", "turn");
            if (sessionManager.isConnected(sessionId)) {
                sender.sendError(sessionId, "AI响应失败: " + e.getMessage());
            }
        } finally {
            state.finishAiSpeaking(AI_SPEAK_COOLDOWN_MS);
        }
    }

    /**
     * 将对话消息落库，失败仅记录日志，不阻断实时链路。
     */
    private void persistMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("Message saved to database for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error saving message for session {}", sessionId, e);
        }
    }

    /** 无会话、append 失败等均可重连 ASR */
    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("No active session") || m.contains("ASR append failed");
    }

    private static boolean isAsrNotReady(IllegalStateException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("ASR session not ready");
    }

    /**
     * Convert exception to user-friendly error message
     */
    private static String getErrorMessage(Exception e) {
        Throwable cause = e.getCause();

        // Check for specific Aliyun errors
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }

        // Default error message
        return "语音处理失败：" + e.getMessage();
    }

    @Override
    public void destroy() {
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 有序 TTS 分块发射器。
     *
     * <p>按句子顺序收集并发 TTS 结果，逐句转 WAV 后以 audio_chunk 消息推给前端，
     * 避免并发合成导致音频乱序。</p>
     */
    private class OrderedTtsChunkEmitter {

        private final String sessionId;
        private final Semaphore ttsSemaphore;
        private final long ttsTimeoutSec;
        private final Map<Integer, CompletableFuture<byte[]>> futures = new ConcurrentHashMap<>();
        private final AtomicInteger nextIndex = new AtomicInteger();
        private final AtomicInteger emittedChunks = new AtomicInteger();
        private final Object lock = new Object();
        private final CompletableFuture<Integer> completion;
        private volatile int totalChunks = -1;

        OrderedTtsChunkEmitter(String sessionId, Semaphore ttsSemaphore, long ttsTimeoutSec) {
            this.sessionId = sessionId;
            this.ttsSemaphore = ttsSemaphore;
            this.ttsTimeoutSec = ttsTimeoutSec;
            this.completion = CompletableFuture.supplyAsync(this::drainChunks, voicePipelineExecutor);
        }

        void submit(String sentence) {
            int index = nextIndex.getAndIncrement();
            ttsSemaphore.acquireUninterruptibly();
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ttsService.synthesize(sentence);
                } finally {
                    ttsSemaphore.release();
                }
            }, voicePipelineExecutor);

            futures.put(index, future);
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        void finish() {
            synchronized (lock) {
                totalChunks = nextIndex.get();
                lock.notifyAll();
            }
        }

        int awaitCompletion() {
            long timeoutSec = Math.max(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()));
            try {
                return completion.get(timeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Session: {}] Streaming TTS chunk emitter did not finish cleanly: {}",
                    sessionId, e.getMessage());
                completion.cancel(true);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sender.sendAudioComplete(sessionId);
                }
                return emitted;
            }
        }

        private int drainChunks() {
            int index = 0;
            try {
                while (true) {
                    CompletableFuture<byte[]> future = waitForFuture(index);
                    if (future == null) {
                        int emitted = emittedChunks.get();
                        if (emitted > 0) {
                            sender.sendAudioComplete(sessionId);
                        }
                        return emitted;
                    }

                    try {
                        byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                        if (pcm != null && pcm.length > 0 && sessionManager.isConnected(sessionId)) {
                            sender.sendAudioChunk(sessionId, AudioConverter.convertPcmToWav(pcm), index, false);
                            emittedChunks.incrementAndGet();
                        }
                    } catch (Exception e) {
                        future.cancel(true);
                        log.warn("[Session: {}] Streaming TTS chunk {} failed", sessionId, index, e);
                    } finally {
                        futures.remove(index);
                        index++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Session: {}] Streaming TTS chunk emitter interrupted", sessionId);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sender.sendAudioComplete(sessionId);
                }
                return emitted;
            }
        }

        private CompletableFuture<byte[]> waitForFuture(int index) throws InterruptedException {
            synchronized (lock) {
                while (!futures.containsKey(index)) {
                    if (totalChunks >= 0 && index >= totalChunks) {
                        return null;
                    }
                    lock.wait(100);
                }
                return futures.get(index);
            }
        }
    }
}
