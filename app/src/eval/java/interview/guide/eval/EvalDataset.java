package interview.guide.eval;

import java.util.List;

/**
 * 评测数据集的 JSON 契约（Jackson 序列化/反序列化）。
 *
 * <p>{@code EvalQuery} 由 Python 侧 {@code gen_testset.py} 生成；
 * {@code RetrievalResults} 由 Java 侧评测 Runner 输出，供 {@code eval_ragas.py} 消费。
 */
public final class EvalDataset {

  private EvalDataset() {
  }

  /**
   * 一条评测 query 及其 ground-truth chunk。
   *
   * @param query              查询文本
   * @param groundTruthChunkIds 相关 chunk 的 {@code vector_store.id}（UUID）
   * @param referenceAnswer    参考回答（供 RAGAS LLM 指标使用）
   * @param referenceContexts  相关 chunk 原文（供 RAGAS LLM 指标使用）
   */
  public record EvalQuery(
      String query,
      List<String> groundTruthChunkIds,
      String referenceAnswer,
      List<String> referenceContexts) {
  }

  /** 检索返回的单个文档（chunk id + 原文） */
  public record RetrievedDoc(String id, String content) {
  }

  /** 单条 query 的检索结果 */
  public record QueryRetrieval(String query, List<RetrievedDoc> retrieved) {
  }

  /** 两种检索方式（纯向量 / 混合检索）的完整结果 */
  public record RetrievalResults(List<QueryRetrieval> vector, List<QueryRetrieval> hybrid) {
  }
}
