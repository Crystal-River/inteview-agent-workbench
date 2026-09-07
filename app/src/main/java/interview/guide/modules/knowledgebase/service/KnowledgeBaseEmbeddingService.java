package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.VectorizationJobEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.repository.VectorizationChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大文件 embedding 批次编排服务。
 *
 * <p>按 (jobId, startIndex, count) 从暂存表读取待嵌入分块，构造确定性 UUID 的 Document，
 * 调用 {@link VectorStore} 流式插入（不做任何索引 DDL），随后原子推进 checkpoint；
 * 剩余归零的消费者胜出触发 finalize（一次性 REINDEX + 新旧向量切换）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseEmbeddingService {

    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private static final String TEMP_KB_ID_PREFIX = "pending:";

    private final VectorStore vectorStore;
    private final VectorizationChunkRepository chunkRepository;
    private final VectorizationJobService jobService;
    private final VectorRepository vectorRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TransactionalExecutor transactionalExecutor;

    public void embedBatch(String jobId, Long kbId, int startIndex, int count) {
        List<VectorizationChunkRepository.VectorizationChunk> chunks =
            chunkRepository.findPendingChunks(jobId, startIndex, count);
        if (chunks.isEmpty()) {
            log.debug("无待嵌入分块，跳过: jobId={}, start={}, count={}", jobId, startIndex, count);
            return;
        }

        List<Document> documents = chunks.stream()
            .map(chunk -> {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(METADATA_KB_ID, TEMP_KB_ID_PREFIX + kbId + ":" + jobId);
                metadata.put(METADATA_TARGET_KB_ID, kbId.toString());
                metadata.put(METADATA_VECTOR_JOB_ID, jobId);
                return new Document(chunk.chunkId(), chunk.content(), metadata);
            })
            .toList();

        // embedding + 向量插入（外部 HTTP 调用，不得在事务内）
        vectorStore.add(documents);

        // 标记已嵌入并原子递减 remaining，避免两者分离导致 remaining 与 PENDING 数不一致
        transactionalExecutor.call(() -> {
            int newlyEmbedded = chunkRepository.markChunksEmbedded(jobId, startIndex, count);
            if (newlyEmbedded > 0) {
                jobService.decrementRemaining(jobId, newlyEmbedded);
            }
            return newlyEmbedded;
        });

        if (jobService.claimFinalize(jobId)) {
            finalize(jobId, kbId);
        }
    }

    private void finalize(String jobId, Long kbId) {
        VectorizationJobEntity job = jobService.findByJobId(jobId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "向量化任务不存在"));
        log.info("开始 finalize: kbId={}, jobId={}, chunks={}", kbId, jobId, job.getTotalChunks());

        try {
            // 完成后一次性重建 HNSW 索引（REINDEX 不放入事务块）
            vectorRepository.rebuildIndex();

            transactionalExecutor.run(() -> {
                vectorRepository.deleteByKnowledgeBaseId(kbId);
                vectorRepository.promoteVectorJob(kbId, jobId);
                knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                    kb.setVectorStatus(VectorStatus.COMPLETED);
                    kb.setChunkCount(job.getTotalChunks());
                    kb.setVectorError(null);
                    knowledgeBaseRepository.save(kb);
                });
            });

            jobService.finishCompleted(jobId);
            log.info("finalize 完成: kbId={}, jobId={}", kbId, jobId);
        } catch (Exception e) {
            log.error("finalize 失败: kbId={}, jobId={}, error={}", kbId, jobId, e.getMessage(), e);
            String error = "finalize 失败: " + e.getMessage();
            jobService.finishFailed(jobId, error);
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(VectorStatus.FAILED);
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
                knowledgeBaseRepository.save(kb);
            });
            cleanupPending(jobId);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量化完成阶段失败: " + e.getMessage());
        }
    }

    /**
     * 恢复被中断的 finalize（job 卡在 FINALIZING 但进程在收尾前崩溃）。
     *
     * <p>finalize 内部的 delete+promote 不可安全重放，但 {@code kb.vectorStatus=COMPLETED}
     * 与 delete+promote 在同一事务提交，据此区分 FINALIZING 的两种子状态：
     * <ul>
     *   <li>KB 已 COMPLETED：事务已提交（delete+promote 成功），仅需补 {@code finishCompleted}。</li>
     *   <li>KB 未 COMPLETED：事务未提交或已回滚，临时向量仍带 {@code pending:} 前缀，
     *       重跑 finalize 安全（{@code deleteByKnowledgeBaseId} 不会误删它们）。</li>
     * </ul>
     */
    public void resumeFinalize(String jobId, Long kbId) {
        boolean completed = knowledgeBaseRepository.findById(kbId)
            .map(kb -> kb.getVectorStatus() == VectorStatus.COMPLETED)
            .orElse(false);
        if (completed) {
            jobService.finishCompleted(jobId);
            log.info("恢复 finalize：事务已提交，仅补记 COMPLETED: kbId={}, jobId={}", kbId, jobId);
            return;
        }
        finalize(jobId, kbId);
    }

    /**
     * 清理 finalize 失败遗留的临时向量数据（best-effort）。
     */
    private void cleanupPending(String jobId) {
        try {
            vectorRepository.deleteByVectorJobId(jobId);
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续补偿: jobId={}, error={}",
                jobId, cleanupError.getMessage(), cleanupError);
        }
    }
}
