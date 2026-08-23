package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.retrieval.HybridRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * KnowledgeBaseVectorService 单元测试
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>向量化存储（vectorizeAndStore）- 分批处理逻辑、metadata 设置、删除旧数据</li>
 *   <li>相似度搜索（similaritySearch）- 基本搜索、知识库ID过滤、topK限制</li>
 *   <li>删除向量数据（deleteByKnowledgeBaseId）</li>
 * </ul>
 *
 * <p>注意：TextSplitter 未被 Mock，测试依赖 TokenTextSplitter 的真实行为。
 * 这是有意为之，因为分词逻辑是向量化的核心部分，应该进行集成测试。
 * 如需完全隔离，可将 TextSplitter 改为构造函数注入。
 */
@DisplayName("知识库向量服务测试")
@SuppressWarnings("unchecked") // Mockito ArgumentCaptor 泛型警告
class KnowledgeBaseVectorServiceTest {

    private KnowledgeBaseVectorService vectorService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorRepository vectorRepository;

    @Mock
    private HybridRetriever hybridRetriever;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vectorService = new KnowledgeBaseVectorService(vectorStore, vectorRepository, hybridRetriever);
    }

    // ==================== 共享辅助方法 ====================

    /**
     * 生成足够长的内容，确保 TokenTextSplitter 产生 chunks
     * TokenTextSplitter 默认配置下，需要较长的文本才会分块
     */
    private String generateLongContent(int paragraphs) {
        StringBuilder contentBuilder = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            contentBuilder.append("这是第 ").append(i).append(" 段内容。")
                .append("Spring Boot 是一个优秀的 Java 框架，它简化了 Spring 应用的开发。")
                .append("通过自动配置和起步依赖，开发者可以快速构建生产级别的应用。")
                .append("Spring AI 提供了与各种 AI 模型交互的能力，包括 embedding 和 chat 功能。")
                .append("PostgreSQL 是一个强大的开源关系数据库，支持向量存储和相似度搜索。")
                .append("通过 pgvector 扩展，可以实现高效的向量索引和检索功能。")
                .append("知识库系统可以将文档内容向量化，然后进行语义搜索，提高检索的准确性。")
                .append("\n\n");
        }
        return contentBuilder.toString();
    }

    /**
     * 创建模拟文档列表
     * @param count 文档数量
     * @param kbId 知识库ID（String 类型），null 表示不设置
     */
    private List<Document> createMockDocuments(int count, String kbId) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> metadata = new HashMap<>();
            if (kbId != null) {
                metadata.put("kb_id", kbId);
            }
            documents.add(new Document("文档内容 " + i, metadata));
        }
        return documents;
    }

    /**
     * 创建模拟文档列表（无 kb_id）
     */
    private List<Document> createMockDocuments(int count) {
        return createMockDocuments(count, null);
    }

    /**
     * 创建使用 Long 类型 kb_id 的文档（模拟旧数据格式）
     */
    private Document createDocumentWithLongKbId(Long kbId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", kbId); // Long 类型
        return new Document("Long kb_id 文档", metadata);
    }

    /**
     * 创建包含无效 kb_id 的文档
     */
    private Document createDocumentWithInvalidKbId(String invalidKbId) {
        Map<String, Object> metadata = new HashMap<>();
        if (invalidKbId != null) {
            metadata.put("kb_id", invalidKbId);
        }
        return new Document("无效 kb_id 文档", metadata);
    }

    /**
     * 创建一个支持过滤的 Mock Answer
     * 简化版本：仅用于测试，手动过滤结果
     * @param allDocuments 所有文档
     * @param allowedKbIds 允许的 kb_id 列表（如果为 null，则返回所有文档）
     */
    private static List<Document> filterDocuments(List<Document> allDocuments, List<Long> allowedKbIds) {
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return allDocuments;
        }

        return allDocuments.stream()
            .filter(doc -> {
                Object kbId = doc.getMetadata().get("kb_id");
                if (kbId == null) {
                    return false;
                }
                String kbIdStr = kbId.toString();
                // 处理 Long 类型 kb_id
                try {
                    Long kbIdLong = Long.parseLong(kbIdStr);
                    return allowedKbIds.contains(kbIdLong);
                } catch (NumberFormatException e) {
                    // String 类型 kb_id，尝试匹配
                    return allowedKbIds.stream().anyMatch(id -> id.toString().equals(kbIdStr));
                }
            })
            .collect(java.util.stream.Collectors.toList());
    }

    // ==================== 测试类 ====================

    @Nested
    @DisplayName("向量化存储测试")
    class VectorizeAndStoreTests {

        @Test
        @DisplayName("文本向量化存储 - 验证基本流程")
        void testVectorizeSmallContent() {
            // Given: 生成足够长的文本以确保产生 chunks
            Long knowledgeBaseId = 1L;
            String content = generateLongContent(5);

            // When: 执行向量化
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 验证所有新数据写入成功后才替换旧数据
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);
            verify(vectorRepository, times(1)).promoteVectorJob(eq(knowledgeBaseId), anyString());

            // 验证 VectorStore.add 被调用（文本足够长时应产生 chunks）
            verify(vectorStore, atLeastOnce()).add(anyList());
        }

        @Test
        @DisplayName("大文本分批处理 - 验证每批不超过限制")
        void testVectorizeLargeContentInBatches() {
            // Given: 生成非常长的文本，确保产生多个 chunks
            Long knowledgeBaseId = 2L;
            // 生成 200 段内容，确保产生足够多的 chunks
            String content = generateLongContent(200);

            // 记录 add 调用
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

            // When: 执行向量化
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 捕获所有 add 调用
            verify(vectorStore, atLeastOnce()).add(captor.capture());

            // 验证每批不超过 10 个（MAX_BATCH_SIZE）
            List<List<Document>> allBatches = captor.getAllValues();
            for (List<Document> batch : allBatches) {
                assertTrue(batch.size() <= 10,
                    "每批次不应超过 10 个文档，实际: " + batch.size());
            }
        }

        @Test
        @DisplayName("验证 metadata 使用临时 kb_id 和任务标记")
        void testMetadataContainsKnowledgeBaseId() {
            // Given: 使用足够长的内容确保产生 chunks
            Long knowledgeBaseId = 123L;
            String content = generateLongContent(10);

            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

            // When
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 捕获添加的文档，验证 metadata
            verify(vectorStore, atLeastOnce()).add(captor.capture());

            List<List<Document>> allBatches = captor.getAllValues();
            assertFalse(allBatches.isEmpty(), "应该有文档被添加");

            for (List<Document> batch : allBatches) {
                for (Document doc : batch) {
                    assertTrue(doc.getMetadata().get("kb_id").toString()
                            .startsWith("pending:" + knowledgeBaseId + ":"),
                        "metadata 中的 kb_id 应该先写入临时值，避免失败时污染正式检索");
                    assertEquals(knowledgeBaseId.toString(), doc.getMetadata().get("kb_target_id"),
                        "metadata 中的 kb_target_id 应该等于目标知识库ID");
                    assertTrue(doc.getMetadata().get("kb_vector_job_id").toString().length() > 10,
                        "metadata 中应包含向量化任务ID");
                }
            }
        }

        @Test
        @DisplayName("向量化成功后才删除旧数据并提升新数据")
        void testDeleteOldDataAfterVectorize() {
            // Given: 使用足够长的内容确保产生 chunks
            Long knowledgeBaseId = 1L;
            String content = generateLongContent(10);

            // When
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 验证 add 成功后再删除旧数据并提升新数据
            var inOrder = inOrder(vectorRepository, vectorStore);
            inOrder.verify(vectorStore, atLeastOnce()).add(anyList());
            inOrder.verify(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);
            inOrder.verify(vectorRepository).promoteVectorJob(eq(knowledgeBaseId), anyString());
        }

        @Test
        @DisplayName("向量写入失败时清理临时数据且不删除旧数据")
        void testVectorizeFailureKeepsOldVectors() {
            // Given: 使用足够长的内容确保产生 chunks
            Long knowledgeBaseId = 1L;
            String content = generateLongContent(10);

            doThrow(new RuntimeException("VectorStore 连接失败"))
                .when(vectorStore).add(anyList());

            // When & Then
            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> vectorService.vectorizeAndStore(knowledgeBaseId, content)
            );

            assertTrue(exception.getMessage().contains("向量化知识库失败"));
            verify(vectorRepository, never()).deleteByKnowledgeBaseId(knowledgeBaseId);
            verify(vectorRepository, never()).promoteVectorJob(eq(knowledgeBaseId), anyString());
            verify(vectorRepository, times(1)).deleteByVectorJobId(anyString());
        }

        @Test
        @DisplayName("空内容处理 - 应该删除旧数据但不添加新数据")
        void testVectorizeEmptyContent() {
            // Given
            Long knowledgeBaseId = 1L;
            String content = "";

            // When
            vectorService.vectorizeAndStore(knowledgeBaseId, content);

            // Then: 空内容成功向量化后会删除旧数据并提升空任务结果
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);
            verify(vectorRepository, times(1)).promoteVectorJob(eq(knowledgeBaseId), anyString());
            // 空内容不会产生 chunks，所以 add 不会被调用
            verify(vectorStore, never()).add(anyList());
        }
    }

    @Nested
    @DisplayName("相似度搜索测试")
    class SimilaritySearchTests {

        @Test
        @DisplayName("相似度搜索委托给混合检索器并透传参数")
        void testDelegatesToHybridRetriever() {
            // Given
            String query = "Java 开发经验";
            List<Long> knowledgeBaseIds = List.of(1L, 2L);
            List<Document> mockResults = createMockDocuments(3, "1");
            when(hybridRetriever.retrieve(query, knowledgeBaseIds, 5, 0.28)).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch(query, knowledgeBaseIds, 5, 0.28);

            // Then
            assertEquals(3, results.size());
            verify(hybridRetriever).retrieve(query, knowledgeBaseIds, 5, 0.28);
        }

        @Test
        @DisplayName("返回混合检索器结果，不做额外处理")
        void testReturnsHybridResultAsIs() {
            // Given
            List<Document> mockResults = createMockDocuments(2, "1");
            when(hybridRetriever.retrieve(anyString(), any(), anyInt(), anyDouble())).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch("查询", null, 5, 0.0);

            // Then
            assertEquals(mockResults, results);
        }

        @Test
        @DisplayName("混合检索器返回空时透传空列表")
        void testEmptyHybridResult() {
            // Given
            when(hybridRetriever.retrieve(anyString(), any(), anyInt(), anyDouble())).thenReturn(List.of());

            // When
            List<Document> results = vectorService.similaritySearch("不存在的内容", null, 10, 0.0);

            // Then
            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("删除向量数据测试")
    class DeleteVectorDataTests {

        @Test
        @DisplayName("成功删除向量数据")
        void testDeleteByKnowledgeBaseId() {
            // Given
            Long knowledgeBaseId = 1L;
            when(vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)).thenReturn(5);

            // When
            vectorService.deleteByKnowledgeBaseId(knowledgeBaseId);

            // Then
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);
        }

        @Test
        @DisplayName("删除失败不抛出异常（静默处理）")
        void testDeleteFailureSilentlyHandled() {
            // Given
            Long knowledgeBaseId = 1L;
            doThrow(new RuntimeException("数据库错误"))
                .when(vectorRepository).deleteByKnowledgeBaseId(knowledgeBaseId);

            // When & Then: 不应该抛出异常
            assertDoesNotThrow(() -> vectorService.deleteByKnowledgeBaseId(knowledgeBaseId));
        }

        @Test
        @DisplayName("删除不存在的知识库数据")
        void testDeleteNonExistentKnowledgeBase() {
            // Given
            Long knowledgeBaseId = 999L;
            when(vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId)).thenReturn(0);

            // When
            vectorService.deleteByKnowledgeBaseId(knowledgeBaseId);

            // Then: 应该正常执行，不抛出异常
            verify(vectorRepository, times(1)).deleteByKnowledgeBaseId(knowledgeBaseId);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("知识库ID为null时 - 应抛出异常并包含有意义的错误信息")
        void testNullKnowledgeBaseId() {
            // Given
            String content = generateLongContent(5);

            // When & Then: null knowledgeBaseId 应该导致 RuntimeException
            // 因为 content.length() 调用在 knowledgeBaseId.toString() 之前，
            // 实际会在设置 metadata 时抛出 NullPointerException，被包装为 RuntimeException
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> vectorService.vectorizeAndStore(null, content)
            );

            assertTrue(exception.getMessage().contains("向量化知识库失败"),
                "异常消息应包含'向量化知识库失败'");
        }

        @Test
        @DisplayName("内容为null时 - 应包装为业务异常")
        void testNullContent() {
            // Given
            Long knowledgeBaseId = 1L;

            // When & Then: null content 应被统一包装为向量化业务异常
            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> vectorService.vectorizeAndStore(knowledgeBaseId, null)
            );
            assertTrue(exception.getMessage().contains("向量化知识库失败"));
        }

        @Test
        @DisplayName("查询字符串为空 - 委托透传空查询")
        void testEmptyQuery() {
            // Given
            String emptyQuery = "";
            when(hybridRetriever.retrieve(emptyQuery, null, 5, 0.0)).thenReturn(List.of());

            // When
            List<Document> results = vectorService.similaritySearch(emptyQuery, null, 5, 0.0);

            // Then
            assertTrue(results.isEmpty());
            verify(hybridRetriever).retrieve(emptyQuery, null, 5, 0.0);
        }

        @Test
        @DisplayName("topK 为 0 - 委托透传并返回空")
        void testTopKZero() {
            // Given
            String query = "测试";
            when(hybridRetriever.retrieve(query, null, 0, 0.0)).thenReturn(List.of());

            // When
            List<Document> results = vectorService.similaritySearch(query, null, 0, 0.0);

            // Then
            assertTrue(results.isEmpty(), "topK=0 应该返回空结果");
            verify(hybridRetriever).retrieve(query, null, 0, 0.0);
        }

        @Test
        @DisplayName("topK 大于实际结果数 - 透传混合检索器结果")
        void testTopKGreaterThanResults() {
            // Given
            String query = "测试";
            int topK = 100;
            List<Document> mockResults = createMockDocuments(5);
            when(hybridRetriever.retrieve(query, null, topK, 0.0)).thenReturn(mockResults);

            // When
            List<Document> results = vectorService.similaritySearch(query, null, topK, 0.0);

            // Then
            assertEquals(5, results.size(), "应该返回所有可用结果");
        }
    }
}
