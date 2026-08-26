package interview.guide.modules.knowledgebase.service;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.model.VectorizationJobEntity;
import interview.guide.modules.knowledgebase.model.VectorizationStatus;
import interview.guide.modules.knowledgebase.repository.VectorizationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 大文件向量化任务生命周期与 checkpoint 管理。
 *
 * <p>进度推进依赖 {@link VectorizationJobRepository} 的单条 UPDATE 保证原子性；
 * {@link TransactionalExecutor} 为 {@code @Modifying} 查询提供事务边界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorizationJobService {

    private final VectorizationJobRepository jobRepository;
    private final TransactionalExecutor transactionalExecutor;

    public String createJob(Long kbId) {
        VectorizationJobEntity job = new VectorizationJobEntity();
        job.setKbId(kbId);
        job.setJobId(UUID.randomUUID().toString());
        job.setStatus(VectorizationStatus.PARSING);
        return jobRepository.save(job).getJobId();
    }

    public Optional<VectorizationJobEntity> findByJobId(String jobId) {
        return jobRepository.findByJobId(jobId);
    }

    public Optional<VectorizationJobEntity> findLatestByKbId(Long kbId) {
        return jobRepository.findFirstByKbIdOrderByIdDesc(kbId);
    }

    /**
     * 解析阶段推进：按本次新插入的分块数累计产出与剩余计数。
     */
    public void incrementProgress(String jobId, int n) {
        transactionalExecutor.run(
            () -> jobRepository.incrementProgress(jobId, n, LocalDateTime.now()));
    }

    /**
     * 解析完成，迁移到 EMBEDDING 阶段（CAS 保证只迁移一次）。
     */
    public boolean markEmbedding(String jobId) {
        return transactionalExecutor.call(() -> jobRepository.markEmbedding(
            jobId, VectorizationStatus.PARSING, VectorizationStatus.EMBEDDING, LocalDateTime.now()) > 0);
    }

    /**
     * 原子递减剩余待嵌入分块数。
     */
    public void decrementRemaining(String jobId, int n) {
        transactionalExecutor.run(
            () -> jobRepository.decrementRemaining(jobId, n, LocalDateTime.now()));
    }

    /**
     * 尝试领取 finalize 权限（仅当 EMBEDDING 且 remaining 归零时成功，并发下唯一胜出）。
     */
    public boolean claimFinalize(String jobId) {
        return transactionalExecutor.call(() -> jobRepository.claimFinalize(
            jobId, VectorizationStatus.EMBEDDING, VectorizationStatus.FINALIZING, LocalDateTime.now()) > 0);
    }

    public void finishCompleted(String jobId) {
        transactionalExecutor.run(
            () -> jobRepository.finish(jobId, VectorizationStatus.COMPLETED, null, LocalDateTime.now()));
    }

    public void finishFailed(String jobId, String error) {
        transactionalExecutor.run(
            () -> jobRepository.finish(jobId, VectorizationStatus.FAILED, truncate(error), LocalDateTime.now()));
    }

    public List<VectorizationJobEntity> findStale(VectorizationStatus status, LocalDateTime threshold) {
        return jobRepository.findStale(status, threshold);
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
