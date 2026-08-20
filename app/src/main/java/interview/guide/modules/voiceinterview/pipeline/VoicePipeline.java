package interview.guide.modules.voiceinterview.pipeline;

/**
 * 语音面试实时链路抽象。
 *
 * <p>定义 WebSocket 连接生命周期与音频/提交入口，隔离 ASR、LLM、TTS 供应商细节，
 * 便于未来替换供应商实现。</p>
 */
public interface VoicePipeline {

    /** WebSocket 连接建立后：启动 ASR、发送欢迎语、触发开场问题。 */
    void onConnected(String sessionId);

    /** WebSocket 连接关闭后：停止 ASR、清理会话状态。 */
    void onDisconnected(String sessionId);

    /** 处理用户上行音频（base64 编码的 PCM）。 */
    void processAudio(String sessionId, String base64Audio);

    /** 提交用户文本触发 LLM 管线；overrideText 可为空表示使用合并缓冲区内容。 */
    void submit(String sessionId, String overrideText);
}
