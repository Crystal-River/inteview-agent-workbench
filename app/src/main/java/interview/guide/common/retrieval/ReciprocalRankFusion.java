package interview.guide.common.retrieval;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合（Reciprocal Rank Fusion, RRF）。
 *
 * <p>融合规则：
 * <ul>
 *   <li>按文档 id 跨路去重聚合（同一文档出现在多路时累加贡献，这正是 RRF 的优点）</li>
 *   <li>score(doc) = Σ_list 1 / (k + rank_in_list)，rank 从 1 开始</li>
 *   <li>按分数降序，截断返回 topK</li>
 * </ul>
 */
public final class ReciprocalRankFusion {

  private ReciprocalRankFusion() {
  }

  /**
   * 融合多个有序文档列表，返回去重后的 topK。
   *
   * @param rankedLists 各路有序召回结果（如向量召回、BM25 召回）
   * @param topK        需要返回的结果数，<= 0 时返回空列表
   * @param k           RRF 平滑常数（通常 60），<= 0 时按 1 处理
   * @return 按融合分数降序的文档列表；分数写入 metadata 的 {@code hybrid_score} 键
   */
  public static List<Document> fuse(List<List<Document>> rankedLists, int topK, double k) {
    if (topK <= 0) {
      return List.of();
    }
    double rrfK = Math.max(k, 1.0);

    Map<String, Double> scores = new HashMap<>();
    Map<String, Document> byId = new HashMap<>();
    for (List<Document> list : rankedLists) {
      if (list == null || list.isEmpty()) {
        continue;
      }
      int rank = 1;
      for (Document doc : list) {
        if (doc == null) {
          rank++;
          continue;
        }
        String id = resolveId(doc);
        if (id == null) {
          rank++;
          continue;
        }
        scores.merge(id, 1.0 / (rrfK + rank), Double::sum);
        byId.putIfAbsent(id, doc);
        rank++;
      }
    }

    List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
    entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

    List<Document> result = new ArrayList<>(Math.min(entries.size(), topK));
    for (Map.Entry<String, Double> entry : entries) {
      if (result.size() >= topK) {
        break;
      }
      Document doc = byId.get(entry.getKey());
      doc.getMetadata().put("hybrid_score", entry.getValue());
      result.add(doc);
    }
    return result;
  }

  private static String resolveId(Document doc) {
    if (doc.getId() != null && !doc.getId().isBlank()) {
      return doc.getId();
    }
    return doc.getText() == null ? null : doc.getText();
  }
}
