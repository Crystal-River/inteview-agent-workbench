package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.messaging.TaskMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大文件 embedding 批次生产者。
 *
 * <p>消息只携带 (jobId, kbId, startChunkIndex, count)，分块文本从暂存表按索引读取。</p>
 */
@Slf4j
@Component
public class EmbeddingBatchProducer extends AbstractStreamProducer<EmbeddingBatchProducer.EmbedBatchPayload> {

    record EmbedBatchPayload(String jobId, Long kbId, int startIndex, int count) {
    }

    public EmbeddingBatchProducer(TaskMessageBroker messageBroker) {
        super(messageBroker);
    }

    /**
     * 发送一个 embedding 批次。
     *
     * @return 是否成功入队
     */
    public boolean sendEmbedBatch(String jobId, Long kbId, int startIndex, int count) {
        return sendTask(new EmbedBatchPayload(jobId, kbId, startIndex, count));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化批次";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_EMBEDDING_STREAM_KEY;
    }

    @Override
    protected int queueMaxLength() {
        return AsyncTaskStreamConstants.KB_EMBEDDING_QUEUE_MAX_LEN;
    }

    @Override
    protected Map<String, String> buildMessage(EmbedBatchPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_JOB_ID, payload.jobId(),
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_CHUNK_START, String.valueOf(payload.startIndex()),
            AsyncTaskStreamConstants.FIELD_CHUNK_COUNT, String.valueOf(payload.count()),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(EmbedBatchPayload payload) {
        return "jobId=" + payload.jobId() + ", start=" + payload.startIndex()
            + ", count=" + payload.count();
    }

    @Override
    protected void onSendFailed(EmbedBatchPayload payload, String error) {
        log.warn("嵌入批次入队失败: jobId={}, start={}, count={}, error={}",
            payload.jobId(), payload.startIndex(), payload.count(), error);
    }
}
