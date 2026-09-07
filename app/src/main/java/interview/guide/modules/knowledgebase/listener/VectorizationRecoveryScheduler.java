package interview.guide.modules.knowledgebase.listener;

import interview.guide.modules.knowledgebase.config.KnowledgeBasePipelineProperties;
import interview.guide.modules.knowledgebase.model.VectorizationJobEntity;
import interview.guide.modules.knowledgebase.model.VectorizationStatus;
import interview.guide.modules.knowledgebase.repository.VectorizationChunkRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseEmbeddingService;
import interview.guide.modules.knowledgebase.service.VectorizationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大文件向量化任务恢复调度器。
 *
 * <p>扫描卡死的 PARSING（重发分块任务）、EMBEDDING（重发未完成批次）与
 * FINALIZING（续跑收尾）任务，实现断点恢复。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorizationRecoveryScheduler {

    private final VectorizationJobService jobService;
    private final VectorizationChunkRepository chunkRepository;
    private final ChunkingBatchProducer chunkingProducer;
    private final EmbeddingBatchProducer embeddingProducer;
    private final KnowledgeBaseEmbeddingService embeddingService;
    private final KnowledgeBasePipelineProperties properties;

    @Scheduled(
        fixedDelayString = "${app.knowledgebase.pipeline.recovery.fixed-delay-ms:60000}",
        initialDelayString = "${app.knowledgebase.pipeline.recovery.fixed-delay-ms:60000}")
    public void recoverStaleJobs() {
        LocalDateTime now = LocalDateTime.now();
        recoverParsing(now.minusMinutes(properties.getRecovery().getParsingStaleMinutes()));
        recoverEmbedding(now.minusMinutes(properties.getRecovery().getEmbeddingStaleMinutes()));
        recoverFinalizing(now.minusMinutes(properties.getRecovery().getFinalizingStaleMinutes()));
    }

    private void recoverParsing(LocalDateTime threshold) {
        List<VectorizationJobEntity> jobs = jobService.findStale(VectorizationStatus.PARSING, threshold);
        for (VectorizationJobEntity job : jobs) {
            chunkingProducer.sendChunkingTask(job.getKbId(), job.getJobId());
            log.warn("恢复卡住的分块任务: kbId={}, jobId={}", job.getKbId(), job.getJobId());
        }
    }

    private void recoverEmbedding(LocalDateTime threshold) {
        List<VectorizationJobEntity> jobs = jobService.findStale(VectorizationStatus.EMBEDDING, threshold);
        for (VectorizationJobEntity job : jobs) {
            if (chunkRepository.findPendingChunkIndexes(job.getJobId()).isEmpty()) {
                continue;
            }
            int total = job.getTotalChunks();
            int batchSize = properties.getEmbedBatchSize();
            for (int start = 0; start < total; start += batchSize) {
                embeddingProducer.sendEmbedBatch(
                    job.getJobId(), job.getKbId(), start, Math.min(batchSize, total - start));
            }
            log.warn("恢复卡住的嵌入任务: kbId={}, jobId={}, total={}",
                job.getKbId(), job.getJobId(), total);
        }
    }

    private void recoverFinalizing(LocalDateTime threshold) {
        List<VectorizationJobEntity> jobs = jobService.findStale(VectorizationStatus.FINALIZING, threshold);
        for (VectorizationJobEntity job : jobs) {
            embeddingService.resumeFinalize(job.getJobId(), job.getKbId());
            log.warn("恢复卡住的 finalize 任务: kbId={}, jobId={}", job.getKbId(), job.getJobId());
        }
    }
}
