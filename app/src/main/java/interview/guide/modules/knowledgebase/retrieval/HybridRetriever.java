package interview.guide.modules.knowledgebase.retrieval;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.retrieval.ReciprocalRankFusion;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.VectorStoreRetriever;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 混合重排检索器：向量召回 + BM25 召回，经 RRF（倒数排名融合）去重、重排后返回 topK。
 *
 * <p>配置关闭或 BM25/融合异常时降级为纯向量召回，保证检索不因关键词侧故障而失败。
 */
@Slf4j
@Component
public class HybridRetriever implements VectorStoreRetriever {

  private static final String METADATA_KB_ID = "kb_id";

  private final VectorStore vectorStore;
  private final Bm25Retriever bm25Retriever;
  private final KnowledgeBaseQueryProperties queryProperties;

  public HybridRetriever(
      VectorStore vectorStore,
      Bm25Retriever bm25Retriever,
      KnowledgeBaseQueryProperties queryProperties) {
    this.vectorStore = vectorStore;
    this.bm25Retriever = bm25Retriever;
    this.queryProperties = queryProperties;
  }

  /**
   * 混合检索主入口。
   *
   * @param query            查询文本
   * @param knowledgeBaseIds 知识库 ID 列表（空则搜全部）
   * @param topK             返回结果数
   * @param minScore         向量侧相似度阈值（仅作用于向量召回）
   * @return 融合后的 topK 文档列表；混合检索关闭时返回纯向量结果
   */
  public List<Document> retrieve(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
    int safeTopK = Math.max(topK, 0);
    if (safeTopK <= 0) {
      return List.of();
    }
    KnowledgeBaseQueryProperties.HybridSearch hybrid = queryProperties.getHybrid();
    if (!hybrid.isEnabled()) {
      return vectorRecall(query, knowledgeBaseIds, safeTopK, minScore);
    }

    int candidateTopK = Math.max(safeTopK * hybrid.getCandidateMultiplier(), 1);
    List<Document> vectorResults = vectorRecall(query, knowledgeBaseIds, candidateTopK, minScore);
    try {
      List<Document> keywordResults = bm25Retriever.retrieve(query, knowledgeBaseIds, candidateTopK);
      List<Document> fused = ReciprocalRankFusion.fuse(
          List.of(vectorResults, keywordResults), safeTopK, hybrid.getRrfK());
      log.info("混合检索完成: query='{}', kbIds={}, topK={}, vector={}, keyword={}, fused={}",
          query, knowledgeBaseIds, safeTopK, vectorResults.size(), keywordResults.size(), fused.size());
      return fused;
    } catch (Exception e) {
      log.warn("BM25/融合失败，降级为纯向量结果: query='{}', error={}", query, e.getMessage(), e);
      return vectorResults.size() > safeTopK
          ? new ArrayList<>(vectorResults.subList(0, safeTopK))
          : vectorResults;
    }
  }

  /**
   * 向量召回（迁移自原 KnowledgeBaseVectorService.similaritySearch，
   * 含前置过滤失败时的本地过滤兜底）。
   */
  private List<Document> vectorRecall(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
    try {
      SearchRequest.Builder builder = SearchRequest.builder()
          .query(query)
          .topK(Math.max(topK, 1));
      if (minScore > 0) {
        builder.similarityThreshold(minScore);
      }
      if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
        builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
      }
      List<Document> results = vectorStore.similaritySearch(builder.build());
      if (results == null) {
        return List.of();
      }
      List<Document> limited = results.stream()
          .limit(Math.max(topK, 1))
          .collect(Collectors.toList());
      log.info("向量相似度搜索: query='{}', kbIds={}, topK={}, minScore={}, hit={}",
          query, knowledgeBaseIds, topK, minScore, limited.size());
      return limited;
    } catch (Exception e) {
      log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage(), e);
      return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
    }
  }

  private List<Document> similaritySearchFallback(
      String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
    try {
      SearchRequest.Builder builder = SearchRequest.builder()
          .query(query)
          .topK(Math.max(topK * 3, topK));
      if (minScore > 0) {
        builder.similarityThreshold(minScore);
      }
      List<Document> allResults = vectorStore.similaritySearch(builder.build());
      if (allResults == null || allResults.isEmpty()) {
        return List.of();
      }
      if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
        allResults = allResults.stream()
            .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
            .collect(Collectors.toList());
      }
      return allResults.stream()
          .limit(Math.max(topK, 1))
          .collect(Collectors.toList());
    } catch (Exception e) {
      log.error("向量搜索失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量搜索失败: " + e.getMessage());
    }
  }

  private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
    Object kbId = doc.getMetadata().get(METADATA_KB_ID);
    if (kbId == null) {
      return false;
    }
    try {
      Long kbIdLong = kbId instanceof Long ? (Long) kbId : Long.parseLong(kbId.toString());
      return knowledgeBaseIds.contains(kbIdLong);
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
    String values = knowledgeBaseIds.stream()
        .filter(Objects::nonNull)
        .map(String::valueOf)
        .map(id -> "'" + id + "'")
        .collect(Collectors.joining(", "));
    return "kb_id in [" + values + "]";
  }

  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    int topK = Math.max(request.getTopK(), 0);
    double minScore = request.getSimilarityThreshold();
    List<Long> kbIds = KbFilterParser.extractKnowledgeBaseIds(request.getFilterExpression());
    return retrieve(request.getQuery(), kbIds, topK, minScore);
  }
}
