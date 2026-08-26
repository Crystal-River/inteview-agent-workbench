package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.messaging.TaskMessageBroker;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.VectorizationStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseChunkingService;
import interview.guide.modules.knowledgebase.service.VectorizationJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大文件分块消费者（单消费者串行，流式解析分块）。
 */
@Slf4j
@Component
public class ChunkingBatchConsumer extends AbstractStreamConsumer<ChunkingBatchConsumer.ChunkingTaskPayload> {

    private final KnowledgeBaseChunkingService chunkingService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final VectorizationJobService jobService;

    record ChunkingTaskPayload(Long kbId, String jobId) {
    }

    public ChunkingBatchConsumer(
        TaskMessageBroker messageBroker,
        KnowledgeBaseChunkingService chunkingService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        VectorizationJobService jobService
    ) {
        super(messageBroker);
        this.chunkingService = chunkingService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.jobService = jobService;
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
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_CHUNKING_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_CHUNKING_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "chunking-consumer";
    }

    @Override
    protected ChunkingTaskPayload parsePayload(String messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String jobId = data.get(AsyncTaskStreamConstants.FIELD_JOB_ID);
        if (kbIdStr == null || jobId == null) {
            log.warn("分块消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new ChunkingTaskPayload(Long.parseLong(kbIdStr), jobId);
    }

    @Override
    protected String payloadIdentifier(ChunkingTaskPayload payload) {
        return "kbId=" + payload.kbId() + ", jobId=" + payload.jobId();
    }

    @Override
    protected boolean shouldSkip(ChunkingTaskPayload payload) {
        if (knowledgeBaseRepository.findById(payload.kbId()).isEmpty()) {
            return true;
        }
        return jobService.findByJobId(payload.jobId())
            .map(job -> job.getStatus() != VectorizationStatus.PARSING)
            .orElse(true);
    }

    @Override
    protected void markProcessing(ChunkingTaskPayload payload) {
        updateKb(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(ChunkingTaskPayload payload) {
        if (!knowledgeBaseRepository.existsById(payload.kbId())) {
            log.warn("知识库已被删除，跳过分块任务: kbId={}", payload.kbId());
            return;
        }
        chunkingService.chunkAndDispatch(payload.kbId(), payload.jobId());
    }

    @Override
    protected void markCompleted(ChunkingTaskPayload payload) {
        jobService.markEmbedding(payload.jobId());
    }

    @Override
    protected void markFailed(ChunkingTaskPayload payload, String error) {
        jobService.finishFailed(payload.jobId(), error);
        updateKb(payload.kbId(), VectorStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(ChunkingTaskPayload payload, int retryCount) {
        republish(Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_JOB_ID, payload.jobId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        ));
    }

    private void updateKb(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            kb.setVectorError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
            knowledgeBaseRepository.save(kb);
        });
    }
}
