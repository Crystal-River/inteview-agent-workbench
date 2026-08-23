package interview.guide.modules.knowledgebase.retrieval;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("混合重排检索器测试")
class HybridRetrieverTest {

  private VectorStore vectorStore;
  private Bm25Retriever bm25Retriever;
  private KnowledgeBaseQueryProperties queryProperties;
  private HybridRetriever hybridRetriever;

  @BeforeEach
  void setUp() {
    vectorStore = mock(VectorStore.class);
    bm25Retriever = mock(Bm25Retriever.class);
    queryProperties = new KnowledgeBaseQueryProperties();
    hybridRetriever = new HybridRetriever(vectorStore, bm25Retriever, queryProperties);
  }

  private Document doc(String id, String text) {
    return new Document(id, text, Map.of("kb_id", "1"));
  }

  @Nested
  @DisplayName("混合融合测试")
  class HybridTests {

    @Test
    @DisplayName("向量与 BM25 结果经 RRF 融合后返回 topK 且无重复")
    void testFusionReturnsTopK() {
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
          doc("v1", "向量一"), doc("v2", "向量二"), doc("v3", "向量三")));
      when(bm25Retriever.retrieve(eq("查询"), eq(List.of(1L)), anyInt())).thenReturn(List.of(
          doc("k1", "关键词一"), doc("v1", "向量一")));

      List<Document> result = hybridRetriever.retrieve("查询", List.of(1L), 2, 0.0);

      assertEquals(2, result.size());
      // v1 出现在两路，融合后应排第一
      assertEquals("v1", result.get(0).getId());
      assertEquals(result.size(), result.stream().map(Document::getId).distinct().count());
    }

    @Test
    @DisplayName("向量无结果但 BM25 有结果时返回 BM25 结果")
    void testKeywordOnlyResults() {
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
      when(bm25Retriever.retrieve(anyString(), anyList(), anyInt())).thenReturn(List.of(
          doc("k1", "关键词一"), doc("k2", "关键词二")));

      List<Document> result = hybridRetriever.retrieve("查询", List.of(1L), 5, 0.0);

      assertEquals(2, result.size());
      assertEquals("k1", result.get(0).getId());
    }

    @Test
    @DisplayName("混合检索关闭时返回纯向量结果且不调用 BM25")
    void testDisabledReturnsVectorOnly() {
      queryProperties.getHybrid().setEnabled(false);
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
          doc("v1", "向量一"), doc("v2", "向量二")));

      List<Document> result = hybridRetriever.retrieve("查询", List.of(1L), 5, 0.0);

      assertEquals(2, result.size());
      verify(bm25Retriever, never()).retrieve(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("BM25 异常时降级为纯向量结果并按 topK 截断")
    void testBm25FailureFallsBackToVector() {
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
          doc("v1", "向量一"), doc("v2", "向量二"), doc("v3", "向量三")));
      when(bm25Retriever.retrieve(anyString(), anyList(), anyInt()))
          .thenThrow(new RuntimeException("BM25 故障"));

      List<Document> result = hybridRetriever.retrieve("查询", List.of(1L), 2, 0.0);

      assertEquals(2, result.size());
      assertEquals("v1", result.get(0).getId());
    }
  }

  @Nested
  @DisplayName("向量召回兜底测试")
  class VectorRecallTests {

    @Test
    @DisplayName("前置过滤失败时回退到本地过滤")
    void testFilterFailureFallsBackToLocalFilter() {
      when(vectorStore.similaritySearch(any(SearchRequest.class)))
          .thenThrow(new RuntimeException("filter 不支持"))
          .thenReturn(List.of(doc("v1", "向量一"), doc("v2", "向量二")));
      when(bm25Retriever.retrieve(anyString(), anyList(), anyInt())).thenReturn(List.of());

      List<Document> result = hybridRetriever.retrieve("查询", List.of(1L), 5, 0.0);

      assertEquals(2, result.size());
    }

    @Test
    @DisplayName("向量搜索彻底失败时抛出业务异常")
    void testVectorFailureThrowsBusinessException() {
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("服务不可用"));

      BusinessException ex = assertThrows(BusinessException.class,
          () -> hybridRetriever.retrieve("查询", List.of(1L), 5, 0.0));

      assertTrue(ex.getMessage().contains("向量搜索失败"));
    }
  }

  @Nested
  @DisplayName("边界条件测试")
  class EdgeCaseTests {

    @Test
    @DisplayName("topK 为 0 返回空且不触达数据源")
    void testTopKZero() {
      assertTrue(hybridRetriever.retrieve("查询", List.of(1L), 0, 0.0).isEmpty());
      verifyNoInteractions(vectorStore, bm25Retriever);
    }

    @Test
    @DisplayName("similaritySearch 提取参数委托 retrieve")
    void testSimilaritySearchDelegates() {
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v1", "向量一")));
      when(bm25Retriever.retrieve(anyString(), anyList(), anyInt())).thenReturn(List.of());

      SearchRequest request = SearchRequest.builder().query("查询").topK(3).build();

      List<Document> result = hybridRetriever.similaritySearch(request);

      assertNotNull(result);
      verify(bm25Retriever).retrieve(eq("查询"), anyList(), anyInt());
    }
  }
}
