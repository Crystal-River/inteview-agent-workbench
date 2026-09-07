package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.StreamingChunker;
import interview.guide.infrastructure.file.StreamingDocumentParseService;
import interview.guide.infrastructure.file.TextCleaningService;
import interview.guide.modules.knowledgebase.config.KnowledgeBasePipelineProperties;
import interview.guide.modules.knowledgebase.listener.EmbeddingBatchProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorizationChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 大文件流式解析分块编排服务。
 *
 * <p>流式下载 → 流式解析 → 逐段清洗 → 有界分块 → 幂等写暂存表 → 发布 embedding 批次。
 * 进度推进与分块写入在同一事务内原子提交，保证重试不重复计数。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseChunkingService {

    private final FileStorageService storageService;
    private final StreamingDocumentParseService parseService;
    private final TextCleaningService textCleaningService;
    private final VectorizationChunkRepository chunkRepository;
    private final VectorizationJobService jobService;
    private final EmbeddingBatchProducer embeddingProducer;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final KnowledgeBasePipelineProperties properties;

    public void chunkAndDispatch(Long kbId, String jobId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        log.info("开始流式解析分块: kbId={}, jobId={}", kbId, jobId);

        StreamingChunker chunker = new StreamingChunker(
            TokenTextSplitter.builder().build(), properties.getChunkBufferSize());
        BatchDispatcher dispatcher = new BatchDispatcher(kbId, jobId);

        try (InputStream input = storageService.downloadFileStream(kb.getStorageKey())) {
            parseService.parseStream(input, properties.getParseFlushSize(), segment -> {
                String cleaned = textCleaningService.cleanText(segment);
                if (!cleaned.isEmpty()) {
                    chunker.feed(cleaned, dispatcher);
                }
            });
            chunker.finish(dispatcher);
        } catch (IOException e) {
            log.error("下载/读取文件失败: kbId={}, error={}", kbId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败: " + e.getMessage());
        }

        dispatcher.flushRemaining();

        if (dispatcher.producedCount() == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        log.info("流式解析分块完成: kbId={}, jobId={}, chunks={}",
            kbId, jobId, dispatcher.producedCount());
    }

    /**
     * 分块批次分发器：把切块器产出的 chunk 累积为 embedding 批次，逐批幂等写暂存表并发布。
     */
    private final class BatchDispatcher implements Consumer<List<String>> {

        private final Long kbId;
        private final String jobId;
        private final List<String> pending = new ArrayList<>();
        private int nextIndex = 0;
        private int producedCount = 0;

        BatchDispatcher(Long kbId, String jobId) {
            this.kbId = kbId;
            this.jobId = jobId;
        }

        @Override
        public void accept(List<String> chunks) {
            pending.addAll(chunks);
            while (pending.size() >= properties.getEmbedBatchSize()) {
                dispatchOne();
            }
        }

        void flushRemaining() {
            while (!pending.isEmpty()) {
                dispatchOne();
            }
        }

        int producedCount() {
            return producedCount;
        }

        private void dispatchOne() {
            int size = Math.min(properties.getEmbedBatchSize(), pending.size());
            List<String> batch = new ArrayList<>(pending.subList(0, size));
            pending.subList(0, size).clear();
            dispatchBatch(batch, nextIndex);
            nextIndex += batch.size();
            producedCount += batch.size();
        }

        private void dispatchBatch(List<String> batch, int startIndex) {
            // 分块写入与进度推进原子提交，重试时不重复计数
            transactionalExecutor.run(() -> {
                int inserted = chunkRepository.insertChunks(jobId, startIndex, batch);
                if (inserted > 0) {
                    jobService.incrementProgress(jobId, inserted);
                }
            });
            if (!embeddingProducer.sendEmbedBatch(jobId, kbId, startIndex, batch.size())) {
                throw new BusinessException(
                        ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "嵌入任务入队失败");
            }
        }
    }
}
