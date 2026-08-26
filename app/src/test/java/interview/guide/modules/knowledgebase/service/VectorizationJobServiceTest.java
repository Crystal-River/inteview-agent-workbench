package interview.guide.modules.knowledgebase.service;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.model.VectorizationJobEntity;
import interview.guide.modules.knowledgebase.model.VectorizationStatus;
import interview.guide.modules.knowledgebase.repository.VectorizationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * VectorizationJobService 单元测试。
 *
 * <p>验证 CAS 迁移（claimFinalize / markEmbedding）的返回值透传、错误信息截断、
 * 任务创建状态。数据库层的「唯一胜出」由 {@code VectorizationJobRepository} 的
 * {@code @Modifying} WHERE 状态守卫保证（详见对应 JPQL）。</p>
 */
@DisplayName("向量化任务生命周期服务测试")
class VectorizationJobServiceTest {

    private VectorizationJobService jobService;

    @Mock
    private VectorizationJobRepository jobRepository;

    private final TransactionalExecutor transactionalExecutor = new TransactionalExecutor();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        jobService = new VectorizationJobService(jobRepository, transactionalExecutor);
    }

    @Nested
    @DisplayName("claimFinalize 唯一胜出透传")
    class ClaimFinalizeTests {

        @Test
        @DisplayName("仓库返回受影响行时领取成功")
        void testClaimFinalizeWins() {
            when(jobRepository.claimFinalize(
                eq("job-1"),
                eq(VectorizationStatus.EMBEDDING),
                eq(VectorizationStatus.FINALIZING),
                any(LocalDateTime.class))).thenReturn(1);

            assertTrue(jobService.claimFinalize("job-1"));
        }

        @Test
        @DisplayName("仓库返回 0 行时领取失败（已被其他消费者胜出）")
        void testClaimFinalizeLoses() {
            when(jobRepository.claimFinalize(
                eq("job-1"),
                eq(VectorizationStatus.EMBEDDING),
                eq(VectorizationStatus.FINALIZING),
                any(LocalDateTime.class))).thenReturn(0);

            assertFalse(jobService.claimFinalize("job-1"));
        }
    }

    @Nested
    @DisplayName("markEmbedding 幂等迁移")
    class MarkEmbeddingTests {

        @Test
        @DisplayName("仅 PARSING→EMBEDDING 迁移成功一次")
        void testMarkEmbeddingReturnsAffectedRows() {
            when(jobRepository.markEmbedding(
                eq("job-1"),
                eq(VectorizationStatus.PARSING),
                eq(VectorizationStatus.EMBEDDING),
                any(LocalDateTime.class))).thenReturn(1).thenReturn(0);

            assertTrue(jobService.markEmbedding("job-1"));
            assertFalse(jobService.markEmbedding("job-1"));
        }
    }

    @Nested
    @DisplayName("任务创建与失败截断")
    class CreateAndFinishTests {

        @Test
        @DisplayName("创建任务生成非空 jobId 且状态为 PARSING")
        void testCreateJob() {
            when(jobRepository.save(any(VectorizationJobEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            String jobId = jobService.createJob(1L);

            assertNotNull(jobId);
            ArgumentCaptor<VectorizationJobEntity> captor =
                ArgumentCaptor.forClass(VectorizationJobEntity.class);
            verify(jobRepository).save(captor.capture());
            assertEquals(1L, captor.getValue().getKbId());
            assertEquals(VectorizationStatus.PARSING, captor.getValue().getStatus());
        }

        @Test
        @DisplayName("失败错误信息超过 500 字符时截断")
        void testFinishFailedTruncatesError() {
            String longError = "x".repeat(600);

            jobService.finishFailed("job-1", longError);

            verify(jobRepository).finish(
                eq("job-1"),
                eq(VectorizationStatus.FAILED),
                eq("x".repeat(500)),
                any(LocalDateTime.class));
        }

        @Test
        @DisplayName("空错误信息原样透传")
        void testFinishFailedNullError() {
            jobService.finishFailed("job-1", null);

            verify(jobRepository).finish(
                eq("job-1"),
                eq(VectorizationStatus.FAILED),
                isNull(),
                any(LocalDateTime.class));
        }
    }
}
