package interview.guide.modules.voiceinterview.session;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个语音会话的实时状态。
 *
 * <p>承载回声抑制（aiSpeaking / aiSpeakEndAt）、LLM 处理互斥（processing）、
 * 多段 STT 定稿合并（mergeBuffer / mergeStartedAt）等并发状态。所有字段均为
 * 原子类型或 volatile，供虚拟线程与 ASR 回调线程安全共享。</p>
 */
public class SessionState {

    private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
    private final AtomicBoolean processing = new AtomicBoolean(false);
    /** AI 正在播放 TTS 音频，期间丢弃麦克风回声 */
    private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
    /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音 */
    private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
    /** 多段 STT completed 拼接，防抖后再送 LLM */
    private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
    /** mergeBuffer 开始计时点，用于「最长等待补充」判定 */
    private final AtomicLong mergeStartedAt = new AtomicLong(0);
    /** 最近一次 STT 活动时间（partial/final） */
    private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
    /** 当前正在执行 LLM+TTS 管线的虚拟线程，断连时可中断 */
    private volatile Thread processingThread = null;

    public void appendFinalSttSegment(String segment) {
        String s = segment == null ? "" : segment.trim();
        if (s.isEmpty()) {
            return;
        }
        mergeBuffer.updateAndGet(prev -> {
            if (prev == null || prev.isEmpty()) {
                mergeStartedAt.set(System.currentTimeMillis());
                return s;
            }
            return joinSegments(prev, s);
        });
        markSttActivity();
    }

    private static String joinSegments(String previous, String next) {
        String trimmedPrevious = previous.trim();
        String trimmedNext = next.trim();
        if (trimmedNext.equals(trimmedPrevious) || trimmedNext.startsWith(trimmedPrevious)) {
            return trimmedNext;
        }
        if (trimmedPrevious.endsWith(trimmedNext)) {
            return trimmedPrevious;
        }
        if (trimmedPrevious.endsWith("。") || trimmedPrevious.endsWith("！")
                || trimmedPrevious.endsWith("？") || trimmedPrevious.endsWith(".")
                || trimmedPrevious.endsWith("!") || trimmedPrevious.endsWith("?")) {
            return trimmedPrevious + " " + trimmedNext;
        }
        return trimmedPrevious + "，" + trimmedNext;
    }

    public String getMergeBufferPreview() {
        String s = mergeBuffer.get();
        return s == null ? "" : s;
    }

    public void setMergeBufferDirectly(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return;
        }
        mergeBuffer.set(s);
        if (mergeStartedAt.get() == 0) {
            mergeStartedAt.set(System.currentTimeMillis());
        }
    }

    public String getMergeBufferPreviewWithPartial(String partial) {
        String current = partial == null ? "" : partial.trim();
        if (current.isEmpty()) {
            return getMergeBufferPreview();
        }
        String confirmed = getMergeBufferPreview();
        if (confirmed.isBlank()) {
            return current;
        }
        return joinSegments(confirmed, current);
    }

    public String takeMergeBufferAndClear() {
        mergeStartedAt.set(0);
        return mergeBuffer.getAndSet("");
    }

    public void markSttActivity() {
        lastSttActivityAt.set(System.currentTimeMillis());
    }

    public long getMergeStartedAt() {
        long value = mergeStartedAt.get();
        return value > 0 ? value : System.currentTimeMillis();
    }

    public long getLastSttActivityAt() {
        return lastSttActivityAt.get();
    }

    public String getAccumulatedText() {
        return accumulatedText.get();
    }

    public void setAccumulatedText(String text) {
        accumulatedText.set(text);
    }

    public AtomicBoolean isProcessing() {
        return processing;
    }

    public void setProcessingThread(Thread t) {
        this.processingThread = t;
    }

    public Thread getProcessingThread() {
        return processingThread;
    }

    public boolean isAiSpeakingOrCooldown() {
        if (aiSpeaking.get()) {
            return true;
        }
        // AI 播放结束后的冷却期，防止扬声器尾音被录入
        return System.currentTimeMillis() < aiSpeakEndAt.get();
    }

    public void startAiSpeaking() {
        aiSpeaking.set(true);
    }

    public void finishAiSpeaking(long cooldownMs) {
        aiSpeaking.set(false);
        aiSpeakEndAt.set(System.currentTimeMillis() + cooldownMs);
    }
}
