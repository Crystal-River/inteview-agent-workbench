package interview.guide.common.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BM25 评分器测试")
class Bm25ScorerTest {

  private final Bm25Scorer scorer = new Bm25Scorer(1.2, 0.75);

  @Test
  @DisplayName("匹配 term 的文档得分大于不匹配的文档")
  void testMatchingDocScoresHigher() {
    List<List<String>> docs = List.of(
        List.of("redis", "stream", "消息", "积压"),
        List.of("java", "spring", "框架")
    );
    double[] scores = scorer.scoreAll(docs, List.of("redis"));
    assertTrue(scores[0] > 0);
    assertEquals(0.0, scores[1], 1e-9);
  }

  @Test
  @DisplayName("越稀有的 term IDF 越高")
  void testRareTermHasHigherIdf() {
    List<List<String>> docs = List.of(
        List.of("redis", "redis"),
        List.of("redis", "stream"),
        List.of("java", "spring")
    );
    double[] rare = scorer.scoreAll(docs, List.of("stream"));
    double[] common = scorer.scoreAll(docs, List.of("redis"));
    assertTrue(rare[1] > common[0], "稀有 term 的得分应高于高频 term");
  }

  @Test
  @DisplayName("相同词频下短文档得分更高（长度归一化）")
  void testShorterDocScoresHigher() {
    List<List<String>> docs = List.of(
        List.of("redis"),
        List.of("redis", "x", "x", "x", "x")
    );
    double[] scores = scorer.scoreAll(docs, List.of("redis"));
    assertTrue(scores[0] > scores[1], "短文档应因长度归一化得分更高");
  }

  @Test
  @DisplayName("文档内词频越高得分越高")
  void testHigherTermFrequencyScoresHigher() {
    List<List<String>> docs = List.of(
        List.of("redis"),
        List.of("redis", "redis", "redis")
    );
    double[] scores = scorer.scoreAll(docs, List.of("redis"));
    assertTrue(scores[1] > scores[0], "词频更高应得分更高");
  }

  @Test
  @DisplayName("空查询或空文档返回零分")
  void testEmptyInputs() {
    assertEquals(0.0, scorer.scoreAll(List.of(List.of("a")), List.of())[0], 1e-9);
    assertEquals(0, scorer.scoreAll(List.of(), List.of("a")).length);
  }
}
