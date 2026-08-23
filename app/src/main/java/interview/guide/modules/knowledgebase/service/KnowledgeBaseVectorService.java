package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.retrieval.HybridRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final HybridRetriever hybridRetriever;

    @Autowired
    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor,
        HybridRetriever hybridRetriever
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        this.hybridRetriever = hybridRetriever;
        // 使用 TokenTextSplitter 默认配置，每个 chunk 约 800 tokens，基于标点边界切分（无重叠）
        this.textSplitter = TokenTextSplitter.builder().build();
    }

    KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository, HybridRetriever hybridRetriever) {
        this(vectorStore, vectorRepository, null, hybridRetriever);
    }

    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        String jobId = null;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}",
                knowledgeBaseId, jobId, content.length());

            // 1. 将文本分块
            List<Document> chunks = textSplitter.apply(
                List.of(new Document(content))
            );
            
            log.info("文本分块完成: {} 个chunks", chunks.size());
            
            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, jobId);

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
            }
            activateVectorJob(knowledgeBaseId, jobId);
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage());
        }
    }

    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
        });
    }
    
    /**
     * 基于多个知识库进行混合检索（向量召回 + BM25 召回 + RRF 融合）
     *
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @param minScore 向量侧相似度阈值（仅作用于向量召回）
     * @return 融合后的相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("混合相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        return hybridRetriever.retrieve(query, knowledgeBaseIds, topK, minScore);
    }

    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     * 
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId));
    }

    private void activateVectorJob(Long knowledgeBaseId, String jobId) {
        runVectorRepositoryMutation(() -> {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        });
    }

    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, cleanupError.getMessage(), cleanupError);
        }
    }

    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }
}
