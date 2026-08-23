package interview.guide.modules.knowledgebase.retrieval;

import interview.guide.common.retrieval.Bm25Scorer;
import interview.guide.common.retrieval.KeywordTokenizer;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStoreRetriever;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 关键词检索器。
 *
 * <p>对 query 分词提取关键词，对知识库分块（{@code vector_store.content}）计算 BM25 分数，按分数降序返回 topN。
 */
@Slf4j
@Component
public class Bm25Retriever implements VectorStoreRetriever {

  private static final String METADATA_KB_ID = "kb_id";
  private static final String METADATA_BM25_SCORE = "bm25_score";

  private final VectorRepository vectorRepository;
  private final Bm25Scorer bm25Scorer;

  public Bm25Retriever(VectorRepository vectorRepository, KnowledgeBaseQueryProperties queryProperties) {
    this.vectorRepository = vectorRepository;
    KnowledgeBaseQueryProperties.HybridSearch hybrid = queryProperties.getHybrid();
    this.bm25Scorer = new Bm25Scorer(hybrid.getBm25K1(), hybrid.getBm25B());
  }

  /**
   * BM25 关键词检索。
   *
   * @param query            查询文本
   * @param knowledgeBaseIds 知识库 ID 列表（空则搜全部，与向量侧"空=搜全部"语义一致）
   * @param topN             返回的候选数
   * @return 按 BM25 分数降序的文档列表；分数写入 metadata 的 {@code bm25_score} 键
   */
  public List<Document> retrieve(String query, List<Long> knowledgeBaseIds, int topN) {
    if (query == null || query.isBlank() || topN <= 0) {
      return List.of();
    }
    List<String> queryTokens = KeywordTokenizer.tokenize(query);
    if (queryTokens.isEmpty()) {
      return List.of();
    }

    List<VectorRepository.VectorChunk> chunks = vectorRepository.findChunksByKnowledgeBaseIds(knowledgeBaseIds);
    if (chunks.isEmpty()) {
      return List.of();
    }

    List<List<String>> docTokens = new ArrayList<>(chunks.size());
    for (VectorRepository.VectorChunk chunk : chunks) {
      docTokens.add(KeywordTokenizer.tokenize(chunk.content()));
    }
    double[] scores = bm25Scorer.scoreAll(docTokens, queryTokens);

    List<Document> result = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      if (scores[i] <= 0) {
        continue;
      }
      VectorRepository.VectorChunk chunk = chunks.get(i);
      Map<String, Object> metadata = new HashMap<>();
      if (chunk.kbId() != null) {
        metadata.put(METADATA_KB_ID, chunk.kbId());
      }
      metadata.put(METADATA_BM25_SCORE, scores[i]);
      result.add(new Document(chunk.id(), chunk.content(), metadata));
    }
    result.sort((a, b) -> Double.compare(
        (double) b.getMetadata().get(METADATA_BM25_SCORE),
        (double) a.getMetadata().get(METADATA_BM25_SCORE)));
    if (result.size() > topN) {
      result = new ArrayList<>(result.subList(0, topN));
    }
    log.info("BM25 检索完成: query='{}', kbIds={}, chunks={}, hit={}", query, knowledgeBaseIds,
        chunks.size(), result.size());
    return result;
  }

  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    int topN = Math.max(request.getTopK(), 1);
    List<Long> kbIds = KbFilterParser.extractKnowledgeBaseIds(request.getFilterExpression());
    return retrieve(request.getQuery(), kbIds, topN);
  }
}
