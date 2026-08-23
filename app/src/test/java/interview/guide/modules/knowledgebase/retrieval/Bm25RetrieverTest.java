package interview.guide.modules.knowledgebase.retrieval;

import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("BM25 检索器测试")
class Bm25RetrieverTest {

  private final VectorRepository vectorRepository = mock(VectorRepository.class);
  private final Bm25Retriever bm25Retriever =
      new Bm25Retriever(vectorRepository, new KnowledgeBaseQueryProperties());

  private final List<VectorRepository.VectorChunk> chunks = List.of(
      new VectorRepository.VectorChunk("c1", "Redis Stream 处理消息积压", "1"),
      new VectorRepository.VectorChunk("c2", "Java 使用 Spring Boot 框架", "1"),
      new VectorRepository.VectorChunk("c3", "Redis 缓存穿透解决方案", "1")
  );

  @Test
  @DisplayName("按关键词命中相关 chunk 并按分数降序返回")
  void testRetrieveRanksByKeyword() {
    when(vectorRepository.findChunksByKnowledgeBaseIds(anyList())).thenReturn(chunks);

    List<Document> docs = bm25Retriever.retrieve("Redis 消息积压", List.of(1L), 5);

    // c2 无关键词命中被排除，c1 命中全部关键词排第一
    assertEquals(2, docs.size());
    assertEquals("c1", docs.get(0).getId());
    assertTrue((double) docs.get(0).getMetadata().get("bm25_score") > 0);
    assertEquals("1", docs.get(0).getMetadata().get("kb_id"));
  }

  @Test
  @DisplayName("topN 截断生效")
  void testTopNTruncation() {
    when(vectorRepository.findChunksByKnowledgeBaseIds(anyList())).thenReturn(List.of(
        new VectorRepository.VectorChunk("c1", "Redis 缓存", "1"),
        new VectorRepository.VectorChunk("c2", "Redis 缓存 穿透", "1"),
        new VectorRepository.VectorChunk("c3", "Redis 分布式锁", "1")
    ));

    List<Document> docs = bm25Retriever.retrieve("Redis", List.of(1L), 2);

    assertEquals(2, docs.size());
  }

  @Test
  @DisplayName("知识库 ID 列表透传给 Repository 过滤")
  void testPassesKbIdsToRepository() {
    when(vectorRepository.findChunksByKnowledgeBaseIds(anyList())).thenReturn(chunks);

    bm25Retriever.retrieve("Redis", List.of(1L, 2L), 3);

    verify(vectorRepository).findChunksByKnowledgeBaseIds(List.of(1L, 2L));
  }

  @Test
  @DisplayName("空查询不读取数据")
  void testEmptyQuerySkipsRepository() {
    assertTrue(bm25Retriever.retrieve("", List.of(1L), 5).isEmpty());
    assertTrue(bm25Retriever.retrieve(null, List.of(1L), 5).isEmpty());
    verifyNoInteractions(vectorRepository);
  }

  @Test
  @DisplayName("无候选 chunk 返回空")
  void testNoChunksReturnsEmpty() {
    when(vectorRepository.findChunksByKnowledgeBaseIds(anyList())).thenReturn(List.of());
    assertTrue(bm25Retriever.retrieve("Redis", List.of(1L), 5).isEmpty());
  }

  @Test
  @DisplayName("similaritySearch 提取 query 与 topK 委托 retrieve")
  void testSimilaritySearchDelegates() {
    when(vectorRepository.findChunksByKnowledgeBaseIds(anyList())).thenReturn(chunks);
    SearchRequest request = SearchRequest.builder().query("Redis").topK(3).build();

    List<Document> docs = bm25Retriever.similaritySearch(request);

    // c2 不含 "redis" 被排除，命中 c1/c3 共 2 条
    assertEquals(2, docs.size());
    verify(vectorRepository).findChunksByKnowledgeBaseIds(anyList());
  }
}
