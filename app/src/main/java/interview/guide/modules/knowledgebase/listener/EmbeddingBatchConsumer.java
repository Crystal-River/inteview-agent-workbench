package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.messaging.TaskMessageBroker;
import interview.guide.modules.knowledgebase.config.KnowledgeBasePipelineProperties;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.VectorizationStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseEmbeddingService;
import interview.guide.modules.knowledgebase.service.VectorizationJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大文件 embedding 批次消费者（并发消费，N 由配置决定）。
 */
@Slf4j
@Component
public class EmbeddingBatchConsumer extends AbstractStreamConsumer<EmbeddingBatchConsumer.EmbedBatchPayload> {

    private final KnowledgeBaseEmbeddingService embeddingService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final VectorizationJobService jobService;
    private final KnowledgeBasePipelineProperties properties;

    record EmbedBatchPayload(String jobId, Long kbId, int startIndex, int count) {
    }

    public EmbeddingBatchConsumer(
        TaskMessageBroker messageBroker,
        KnowledgeBaseEmbeddingService embeddingService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        VectorizationJobService jobService,
        KnowledgeBasePipelineProperties properties
    ) {
        super(messageBroker);
        this.embeddingService = embeddingService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.jobService = jobService;
        this.properties = properties;
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
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_EMBEDDING_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_EMBEDDING_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "embedding-consumer";
    }

    @Override
    protected int consumerConcurrency() {
        return properties.getEmbeddingConcurrency();
    }

    @Override
    protected int queueMaxLength() {
        return AsyncTaskStreamConstants.KB_EMBEDDING_QUEUE_MAX_LEN;
    }

    @Override
    protected EmbedBatchPayload parsePayload(String messageId, Map<String, String> data) {
        String jobId = data.get(AsyncTaskStreamConstants.FIELD_JOB_ID);
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String startStr = data.get(AsyncTaskStreamConstants.FIELD_CHUNK_START);
        String countStr = data.get(AsyncTaskStreamConstants.FIELD_CHUNK_COUNT);
        if (jobId == null || kbIdStr == null || startStr == null || countStr == null) {
            log.warn("嵌入批次消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new EmbedBatchPayload(
            jobId, Long.parseLong(kbIdStr), Integer.parseInt(startStr), Integer.parseInt(countStr));
    }

    @Override
    protected String payloadIdentifier(EmbedBatchPayload payload) {
        return "jobId=" + payload.jobId() + ", start=" + payload.startIndex()
            + ", count=" + payload.count();
    }

    @Override
    protected boolean shouldSkip(EmbedBatchPayload payload) {
        if (knowledgeBaseRepository.findById(payload.kbId()).isEmpty()) {
            return true;
        }
        return jobService.findByJobId(payload.jobId())
            .map(job -> job.getStatus() == VectorizationStatus.COMPLETED
                || job.getStatus() == VectorizationStatus.FAILED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(EmbedBatchPayload payload) {
        // 知识库状态在 finalize 前保持 PROCESSING
    }

    @Override
    protected void processBusiness(EmbedBatchPayload payload) {
        embeddingService.embedBatch(payload.jobId(), payload.kbId(), payload.startIndex(), payload.count());
    }

    @Override
    protected void markCompleted(EmbedBatchPayload payload) {
        // 由 finalize 内部更新知识库状态为 COMPLETED
    }

    @Override
    protected void markFailed(EmbedBatchPayload payload, String error) {
        jobService.finishFailed(payload.jobId(), error);
        updateKb(payload.kbId(), VectorStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(EmbedBatchPayload payload, int retryCount) {
        republish(Map.of(
            AsyncTaskStreamConstants.FIELD_JOB_ID, payload.jobId(),
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_CHUNK_START, String.valueOf(payload.startIndex()),
            AsyncTaskStreamConstants.FIELD_CHUNK_COUNT, String.valueOf(payload.count()),
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
