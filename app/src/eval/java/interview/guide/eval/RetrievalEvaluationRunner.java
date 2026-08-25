package interview.guide.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.knowledgebase.retrieval.HybridRetriever;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索评测 Runner：读取 Python 生成的测试集，分别调用纯向量召回与混合检索，
 * 计算 Recall@K 与 MRR 并输出检索结果供 RAGAS 计算 LLM 指标。
 *
 * <p>仅在 {@code app.eval.enabled=true} 时生效，跑完即关闭应用（一次性评测）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.eval.enabled", havingValue = "true")
public class RetrievalEvaluationRunner implements ApplicationRunner {

  private final HybridRetriever hybridRetriever;
  private final DemoKnowledgeBaseSeeder seeder;
  private final KnowledgeBaseQueryProperties queryProperties;
  private final EvalProperties evalProperties;
  private final ObjectMapper objectMapper;
  private final ConfigurableApplicationContext context;

  @Override
  public void run(ApplicationArguments args) throws Exception {
    try {
      if (evalProperties.isOnlySeed()) {
        seeder.seed();
        log.info("演示知识库已灌入，评测跳过。");
        return;
      }
      runEvaluation();
    } finally {
      System.exit(SpringApplication.exit(context, () -> 0));
    }
  }

  private void runEvaluation() throws Exception {
    File testsetFile = new File(evalProperties.getTestsetPath());
    if (!testsetFile.exists()) {
      log.error("评测数据集不存在: {}，请先运行 scripts/eval/gen_testset.py 生成。",
          testsetFile.getAbsolutePath());
      return;
    }
    List<EvalDataset.EvalQuery> queries = objectMapper.readValue(
        testsetFile, new TypeReference<List<EvalDataset.EvalQuery>>() {
        });

    List<Long> kbIds = evalProperties.getKbIds();
    int topK = evalProperties.getTopK();
    double minScore = evalProperties.getMinScore();

    List<EvalDataset.QueryRetrieval> vectorResults = new ArrayList<>();
    List<EvalDataset.QueryRetrieval> hybridResults = new ArrayList<>();

    List<Double> vectorMrr = new ArrayList<>();
    List<Double> hybridMrr = new ArrayList<>();
    Map<Integer, List<Double>> vectorRecall = new LinkedHashMap<>();
    Map<Integer, List<Double>> hybridRecall = new LinkedHashMap<>();
    for (int k : evalProperties.getKValues()) {
      vectorRecall.put(k, new ArrayList<>());
      hybridRecall.put(k, new ArrayList<>());
    }

    for (EvalDataset.EvalQuery q : queries) {
      Set<String> groundTruth = new HashSet<>(q.groundTruthChunkIds());

      List<Document> vectorDocs = vectorSearch(q.query(), kbIds, topK, minScore);
      vectorResults.add(toQueryRetrieval(q.query(), vectorDocs));

      List<Document> hybridDocs = hybridSearch(q.query(), kbIds, topK, minScore);
      hybridResults.add(toQueryRetrieval(q.query(), hybridDocs));

      List<String> vectorIds = vectorDocs.stream().map(Document::getId).toList();
      List<String> hybridIds = hybridDocs.stream().map(Document::getId).toList();

      vectorMrr.add(RetrievalMetrics.mrr(vectorIds, groundTruth));
      hybridMrr.add(RetrievalMetrics.mrr(hybridIds, groundTruth));
      for (int k : evalProperties.getKValues()) {
        vectorRecall.get(k).add(RetrievalMetrics.recallAtK(vectorIds, groundTruth, k));
        hybridRecall.get(k).add(RetrievalMetrics.recallAtK(hybridIds, groundTruth, k));
      }
    }

    EvalDataset.RetrievalResults results = new EvalDataset.RetrievalResults(vectorResults, hybridResults);
    objectMapper.writerWithDefaultPrettyPrinter()
        .writeValue(new File(evalProperties.getOutputPath()), results);

    printReport(queries.size(), vectorRecall, hybridRecall, vectorMrr, hybridMrr);
  }

  /** 纯向量召回：临时关闭混合检索开关，走 {@link HybridRetriever#retrieve} 的向量分支。 */
  private List<Document> vectorSearch(String query, List<Long> kbIds, int topK, double minScore) {
    boolean original = queryProperties.getHybrid().isEnabled();
    queryProperties.getHybrid().setEnabled(false);
    try {
      return hybridRetriever.retrieve(query, kbIds, topK, minScore);
    } finally {
      queryProperties.getHybrid().setEnabled(original);
    }
  }

  /** 混合检索：临时开启混合检索开关，确保评测到 BM25 + RRF 融合路径。 */
  private List<Document> hybridSearch(String query, List<Long> kbIds, int topK, double minScore) {
    boolean original = queryProperties.getHybrid().isEnabled();
    queryProperties.getHybrid().setEnabled(true);
    try {
      return hybridRetriever.retrieve(query, kbIds, topK, minScore);
    } finally {
      queryProperties.getHybrid().setEnabled(original);
    }
  }

  private EvalDataset.QueryRetrieval toQueryRetrieval(String query, List<Document> docs) {
    List<EvalDataset.RetrievedDoc> retrieved = docs.stream()
        .map(d -> new EvalDataset.RetrievedDoc(d.getId(), d.getText()))
        .toList();
    return new EvalDataset.QueryRetrieval(query, retrieved);
  }

  private void printReport(int total, Map<Integer, List<Double>> vectorRecall,
      Map<Integer, List<Double>> hybridRecall, List<Double> vectorMrr, List<Double> hybridMrr) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n========== RAG 检索评估报告 ==========\n");
    sb.append("查询数: ").append(total).append("\n\n");
    sb.append(String.format("%-14s %-12s %-12s%n", "指标", "纯向量", "混合检索"));
    for (int k : evalProperties.getKValues()) {
      double v = RetrievalMetrics.mean(vectorRecall.get(k));
      double h = RetrievalMetrics.mean(hybridRecall.get(k));
      sb.append(String.format("Recall@%-9d %-12.4f %-12.4f%n", k, v, h));
    }
    sb.append(String.format("%-14s %-12.4f %-12.4f%n",
        "MRR", RetrievalMetrics.mean(vectorMrr), RetrievalMetrics.mean(hybridMrr)));
    sb.append("=======================================\n");
    System.out.println(sb);
    log.info("评测完成，检索结果已写入 {}", evalProperties.getOutputPath());
  }
}
