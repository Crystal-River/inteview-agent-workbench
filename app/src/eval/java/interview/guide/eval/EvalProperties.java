package interview.guide.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索评测配置，前缀 {@code app.eval}。
 *
 * <p>默认关闭，仅当 {@code app.eval.enabled=true} 时才会创建评测 Runner 与 Seeder，
 * 不影响正常业务流程。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.eval")
public class EvalProperties {

  /** 评测总开关，关闭时不会创建任何评测组件 */
  private boolean enabled = false;

  /** 仅灌入演示知识库后退出，不执行检索评测 */
  private boolean onlySeed = false;

  /** 演示知识库 ID（仅写入 vector_store 的 kb_id 元数据，不建 knowledge_bases 行） */
  private Long demoKbId = 900001L;

  /** 每次查询召回的最大候选数（topK） */
  private int topK = 10;

  /** 计算 Recall@K 的 K 取值 */
  private List<Integer> kValues = new ArrayList<>(List.of(1, 3, 5, 10));

  /** 向量侧相似度阈值，0 表示不启用阈值过滤（评测纯排序） */
  private double minScore = 0.0;

  /** 检索时限制的知识库 ID 列表，空表示搜索全部 */
  private List<Long> kbIds = new ArrayList<>();

  /** 评测数据集路径（由 scripts/eval/gen_testset.py 生成） */
  private String testsetPath = "../scripts/eval/testset.json";

  /** 检索结果输出路径（供 scripts/eval/eval_ragas.py 消费） */
  private String outputPath = "../scripts/eval/retrieval_results.json";
}
