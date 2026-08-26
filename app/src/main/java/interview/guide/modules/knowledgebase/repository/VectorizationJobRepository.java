package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.VectorizationJobEntity;
import interview.guide.modules.knowledgebase.model.VectorizationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 大文件向量化任务 checkpoint Repository。
 *
 * <p>进度推进使用 {@link Modifying} 单条 UPDATE，保证并发下的原子性；
 * {@code updatedAt} 每次随进度更新，供恢复调度器判定卡死任务。</p>
 */
@Repository
public interface VectorizationJobRepository extends JpaRepository<VectorizationJobEntity, Long> {

    Optional<VectorizationJobEntity> findByJobId(String jobId);

    Optional<VectorizationJobEntity> findFirstByKbIdOrderByIdDesc(Long kbId);

    /**
     * 解析阶段推进：累计已产出（已暂存）的分块数，同时累加剩余待嵌入数。
     * 只按「本次新插入」的分块数推进，保证重复解析（重试）不重复计数。
     */
    @Modifying
    @Query("UPDATE VectorizationJobEntity j SET j.emittedChunks = j.emittedChunks + :n, "
        + "j.remaining = j.remaining + :n, j.updatedAt = :now WHERE j.jobId = :jobId")
    int incrementProgress(
        @Param("jobId") String jobId,
        @Param("n") int n,
        @Param("now") LocalDateTime now);

    /**
     * 解析完成，进入 embedding 阶段：把已产出的分块数固化为总量。
     * 用 status CAS 保证幂等（仅 PARSING 可迁移），remaining 已随 progress 正确累计。
     */
    @Modifying
    @Query("UPDATE VectorizationJobEntity j SET j.totalChunks = j.emittedChunks, "
        + "j.status = :embedding, j.updatedAt = :now "
        + "WHERE j.jobId = :jobId AND j.status = :parsing")
    int markEmbedding(
        @Param("jobId") String jobId,
        @Param("parsing") VectorizationStatus parsing,
        @Param("embedding") VectorizationStatus embedding,
        @Param("now") LocalDateTime now);

    /**
     * 原子递减剩余待嵌入分块数。
     */
    @Modifying
    @Query("UPDATE VectorizationJobEntity j SET j.remaining = j.remaining - :n, "
        + "j.updatedAt = :now WHERE j.jobId = :jobId")
    int decrementRemaining(
        @Param("jobId") String jobId,
        @Param("n") int n,
        @Param("now") LocalDateTime now);

    /**
     * 尝试领取 finalize 权限。仅当 status=EMBEDDING 且 remaining 已归零时成功，
     * 通过 CAS 保证并发下只有一个消费者胜出。
     */
    @Modifying
    @Query("UPDATE VectorizationJobEntity j SET j.status = :finalizing, j.updatedAt = :now "
        + "WHERE j.jobId = :jobId AND j.status = :embedding AND j.remaining <= 0")
    int claimFinalize(
        @Param("jobId") String jobId,
        @Param("embedding") VectorizationStatus embedding,
        @Param("finalizing") VectorizationStatus finalizing,
        @Param("now") LocalDateTime now);

    /**
     * 终态写入（COMPLETED / FAILED），error 可为 null。
     */
    @Modifying
    @Query("UPDATE VectorizationJobEntity j SET j.status = :status, j.error = :error, "
        + "j.updatedAt = :now WHERE j.jobId = :jobId")
    int finish(
        @Param("jobId") String jobId,
        @Param("status") VectorizationStatus status,
        @Param("error") String error,
        @Param("now") LocalDateTime now);

    @Query("SELECT j FROM VectorizationJobEntity j "
        + "WHERE j.status = :status AND j.updatedAt < :threshold ORDER BY j.id")
    List<VectorizationJobEntity> findStale(
        @Param("status") VectorizationStatus status,
        @Param("threshold") LocalDateTime threshold);
}
