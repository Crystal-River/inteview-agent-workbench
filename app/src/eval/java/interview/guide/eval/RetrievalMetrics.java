package interview.guide.eval;

import java.util.List;
import java.util.Set;

/**
 * 检索排序指标（确定性，无 LLM 依赖）。
 */
public final class RetrievalMetrics {

  private RetrievalMetrics() {
  }

  /**
   * Recall@K：前 K 个结果中命中的 ground-truth 数量占 ground-truth 总数的比例。
   *
   * @param retrievedIds 按相关度降序排列的文档 id 列表
   * @param groundTruth  ground-truth 文档 id 集合
   * @param k            截断位置
   * @return [0,1] 区间的召回率
   */
  public static double recallAtK(List<String> retrievedIds, Set<String> groundTruth, int k) {
    if (groundTruth.isEmpty() || k <= 0) {
      return 0.0;
    }
    long hits = retrievedIds.stream().limit(k).filter(groundTruth::contains).count();
    return (double) hits / groundTruth.size();
  }

  /**
   * MRR：首个命中的 ground-truth 文档所在位置（1-based）的倒数，未命中记 0。
   */
  public static double mrr(List<String> retrievedIds, Set<String> groundTruth) {
    for (int i = 0; i < retrievedIds.size(); i++) {
      if (groundTruth.contains(retrievedIds.get(i))) {
        return 1.0 / (i + 1);
      }
    }
    return 0.0;
  }

  /** 一组分值的算术平均，空集返回 0。 */
  public static double mean(List<Double> values) {
    if (values.isEmpty()) {
      return 0.0;
    }
    return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
  }
}
