package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.VectorizationJobEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.repository.VectorizationChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * KnowledgeBaseEmbeddingService 单元测试。
 *
 * <p>覆盖单批嵌入的 checkpoint 原子推进、finalize 的唯一胜出（幂等）与失败清理。
 * 使用真实 {@link TransactionalExecutor}（无代理时直接执行 lambda），其余依赖 Mock。</p>
 */
@DisplayName("知识库嵌入编排服务测试")
@SuppressWarnings("unchecked") // Mockito ArgumentCaptor 泛型警告
class KnowledgeBaseEmbeddingServiceTest {

    private static final String JOB_ID = "job-1";
    private static final Long KB_ID = 1L;
    private static final int START = 0;
    private static final int COUNT = 10;

    private KnowledgeBaseEmbeddingService embeddingService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorizationChunkRepository chunkRepository;

    @Mock
    private VectorizationJobService jobService;

    @Mock
    private VectorRepository vectorRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private final TransactionalExecutor transactionalExecutor = new TransactionalExecutor();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        embeddingService = new KnowledgeBaseEmbeddingService(
            vectorStore, chunkRepository, jobService, vectorRepository,
            knowledgeBaseRepository, transactionalExecutor);
    }

    private VectorizationChunkRepository.VectorizationChunk pendingChunk() {
        return new VectorizationChunkRepository.VectorizationChunk(0, "chunk-uuid", "分块内容");
    }

    private VectorizationJobEntity job(int totalChunks) {
        VectorizationJobEntity job = new VectorizationJobEntity();
        job.setJobId(JOB_ID);
        job.setKbId(KB_ID);
        job.setTotalChunks(totalChunks);
        return job;
    }

    @Nested
    @DisplayName("单批嵌入与 finalize")
    class EmbedBatchTests {

        @Test
        @DisplayName("claimFinalize 胜出时执行 finalize 并完成向量切换")
        void testEmbedBatchWinsFinalize() {
            VectorizationChunkRepository.VectorizationChunk chunk = pendingChunk();
            when(chunkRepository.findPendingChunks(JOB_ID, START, COUNT)).thenReturn(List.of(chunk));
            when(chunkRepository.markChunksEmbedded(JOB_ID, START, COUNT)).thenReturn(1);
            when(jobService.claimFinalize(JOB_ID)).thenReturn(true);
            when(jobService.findByJobId(JOB_ID)).thenReturn(Optional.of(job(5)));
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(kb));

            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            embeddingService.embedBatch(JOB_ID, KB_ID, START, COUNT);

            // 构造的 Document 使用确定性 ID 与 pending metadata
            verify(vectorStore).add(captor.capture());
            List<Document> documents = captor.getValue();
            assertEquals(1, documents.size());
            Document doc = documents.get(0);
            assertEquals(chunk.chunkId(), doc.getId());
            assertEquals(chunk.content(), doc.getText());
            assertEquals("pending:" + KB_ID + ":" + JOB_ID, doc.getMetadata().get("kb_id"));
            assertEquals(KB_ID.toString(), doc.getMetadata().get("kb_target_id"));
            assertEquals(JOB_ID, doc.getMetadata().get("kb_vector_job_id"));

            // checkpoint 原子推进
            verify(chunkRepository).markChunksEmbedded(JOB_ID, START, COUNT);
            verify(jobService).decrementRemaining(JOB_ID, 1);

            // finalize 内重建索引 + 切换向量 + 更新知识库状态
            verify(vectorRepository).rebuildIndex();
            verify(vectorRepository).deleteByKnowledgeBaseId(KB_ID);
            verify(vectorRepository).promoteVectorJob(KB_ID, JOB_ID);
            verify(jobService).finishCompleted(JOB_ID);
            assertEquals(VectorStatus.COMPLETED, kb.getVectorStatus());
            assertEquals(5, kb.getChunkCount());
        }

        @Test
        @DisplayName("claimFinalize 未胜出时不执行 finalize（幂等）")
        void testEmbedBatchDoesNotFinalizeWhenClaimLost() {
            when(chunkRepository.findPendingChunks(JOB_ID, START, COUNT))
                .thenReturn(List.of(pendingChunk()));
            when(chunkRepository.markChunksEmbedded(JOB_ID, START, COUNT)).thenReturn(1);
            when(jobService.claimFinalize(JOB_ID)).thenReturn(false);

            embeddingService.embedBatch(JOB_ID, KB_ID, START, COUNT);

            verify(vectorStore).add(anyList());
            verify(vectorRepository, never()).rebuildIndex();
            verify(vectorRepository, never()).deleteByKnowledgeBaseId(any());
            verify(vectorRepository, never()).promoteVectorJob(any(), anyString());
            verify(jobService, never()).finishCompleted(JOB_ID);
        }

        @Test
        @DisplayName("无待嵌入分块时提前返回")
        void testEmptyChunksReturnsEarly() {
            when(chunkRepository.findPendingChunks(JOB_ID, START, COUNT)).thenReturn(List.of());

            embeddingService.embedBatch(JOB_ID, KB_ID, START, COUNT);

            verify(vectorStore, never()).add(anyList());
            verify(chunkRepository, never()).markChunksEmbedded(anyString(), anyInt(), anyInt());
            verify(jobService, never()).claimFinalize(JOB_ID);
        }
    }

    @Nested
    @DisplayName("finalize 失败清理")
    class FinalizeFailureTests {

        @Test
        @DisplayName("重建索引失败时标记任务/知识库失败并清理临时向量")
        void testFinalizeFailureMarksFailedAndCleansUp() {
            when(chunkRepository.findPendingChunks(JOB_ID, START, COUNT))
                .thenReturn(List.of(pendingChunk()));
            when(chunkRepository.markChunksEmbedded(JOB_ID, START, COUNT)).thenReturn(1);
            when(jobService.claimFinalize(JOB_ID)).thenReturn(true);
            when(jobService.findByJobId(JOB_ID)).thenReturn(Optional.of(job(5)));
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(kb));
            doThrow(new RuntimeException("REINDEX failed")).when(vectorRepository).rebuildIndex();

            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> embeddingService.embedBatch(JOB_ID, KB_ID, START, COUNT));

            assertTrue(exception.getMessage().contains("向量化完成阶段失败"));
            verify(jobService).finishFailed(eq(JOB_ID), contains("finalize 失败"));
            assertEquals(VectorStatus.FAILED, kb.getVectorStatus());
            verify(vectorRepository).deleteByVectorJobId(JOB_ID);
        }
    }
}
