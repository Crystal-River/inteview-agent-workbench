package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.messaging.TaskMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大文件分块任务生产者。
 *
 * <p>消息只携带 (kbId, jobId)，分块文本经暂存表传递，避免大文本内联进消息体。</p>
 */
@Slf4j
@Component
public class ChunkingBatchProducer extends AbstractStreamProducer<ChunkingBatchProducer.ChunkingTaskPayload> {

    record ChunkingTaskPayload(Long kbId, String jobId) {
    }

    public ChunkingBatchProducer(TaskMessageBroker messageBroker) {
        super(messageBroker);
    }

    /**
     * 发送分块任务。
     *
     * @return 是否成功入队
     */
    public boolean sendChunkingTask(Long kbId, String jobId) {
        return sendTask(new ChunkingTaskPayload(kbId, jobId));
    }

    @Override
    protected String taskDisplayName() {
        return "分块";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_CHUNKING_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(ChunkingTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_JOB_ID, payload.jobId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(ChunkingTaskPayload payload) {
        return "kbId=" + payload.kbId() + ", jobId=" + payload.jobId();
    }

    @Override
    protected void onSendFailed(ChunkingTaskPayload payload, String error) {
        log.warn("分块任务入队失败: kbId={}, jobId={}, error={}",
            payload.kbId(), payload.jobId(), error);
    }
}
