package interview.guide.common.retrieval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 评分器（纯函数，无状态依赖、可单测）。
 *
 * <p>标准 BM25：
 * <pre>
 * score(D,Q) = Σ_{t∈Q} idf(t) * f(t,D) * (k1 + 1) / (f(t,D) + k1 * (1 - b + b * |D| / avgdl))
 * idf(t)     = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
 * </pre>
 * k1 控制词频饱和度（默认 1.2），b 控制文档长度归一化强度（默认 0.75）。
 */
public final class Bm25Scorer {

  private final double k1;
  private final double b;

  public Bm25Scorer(double k1, double b) {
    this.k1 = k1;
    this.b = b;
  }

  /**
   * 计算查询 token 对一组文档的 BM25 分数。
   *
   * @param documents   文档 token 列表，顺序与外部 Document 保持一致
   * @param queryTokens 查询 token（已由 {@link KeywordTokenizer} 分词）
   * @return 与 documents 等长的分数数组；query 或 documents 为空时全为 0
   */
  public double[] scoreAll(List<List<String>> documents, List<String> queryTokens) {
    int n = documents.size();
    double[] scores = new double[n];
    if (queryTokens.isEmpty() || n == 0) {
      return scores;
    }

    Map<String, Double> idf = computeIdf(documents, queryTokens);
    int[] docLens = new int[n];
    double totalLen = 0;
    for (int i = 0; i < n; i++) {
      docLens[i] = documents.get(i).size();
      totalLen += docLens[i];
    }
    if (totalLen <= 0) {
      return scores;
    }
    double avgdl = totalLen / (double) n;

    for (int i = 0; i < n; i++) {
      if (docLens[i] == 0) {
        continue;
      }
      Map<String, Integer> tf = countTerms(documents.get(i));
      double score = 0;
      for (String term : queryTokens) {
        Double idfValue = idf.get(term);
        if (idfValue == null) {
          continue;
        }
        int f = tf.getOrDefault(term, 0);
        if (f == 0) {
          continue;
        }
        double lengthNorm = 1 - b + b * docLens[i] / avgdl;
        score += idfValue * (f * (k1 + 1)) / (f + k1 * lengthNorm);
      }
      scores[i] = score;
    }
    return scores;
  }

  /**
   * 计算 query 中每个 term 的文档频率与 IDF（仅统计出现在 query 中的 term，避免全量词典）。
   */
  private Map<String, Double> computeIdf(List<List<String>> documents, List<String> queryTokens) {
    Map<String, Integer> df = new HashMap<>();
    for (String term : queryTokens) {
      int count = 0;
      for (List<String> doc : documents) {
        if (doc.contains(term)) {
          count++;
        }
      }
      if (count > 0) {
        df.put(term, count);
      }
    }
    int n = documents.size();
    Map<String, Double> idf = new HashMap<>();
    for (Map.Entry<String, Integer> entry : df.entrySet()) {
      double dft = entry.getValue();
      idf.put(entry.getKey(), Math.log(1 + (n - dft + 0.5) / (dft + 0.5)));
    }
    return idf;
  }

  private Map<String, Integer> countTerms(List<String> doc) {
    Map<String, Integer> tf = new HashMap<>();
    for (String term : doc) {
      tf.merge(term, 1, Integer::sum);
    }
    return tf;
  }
}
