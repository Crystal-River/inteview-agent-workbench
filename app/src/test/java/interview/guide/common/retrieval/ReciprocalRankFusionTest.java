package interview.guide.common.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RRF 融合测试")
class ReciprocalRankFusionTest {

  @Test
  @DisplayName("两路都出现的文档排在最前")
  void testDocInBothListsRanksFirst() {
    Document d1 = doc("d1");
    Document d2 = doc("d2");
    Document d3 = doc("d3");
    List<Document> fused = ReciprocalRankFusion.fuse(
        List.of(List.of(d1, d2), List.of(d1, d3)), 3, 60);
    assertEquals("d1", fused.get(0).getId());
    assertEquals(3, fused.size());
  }

  @Test
  @DisplayName("跨路重复文档只出现一次")
  void testDedupAcrossLists() {
    Document d1 = doc("d1");
    List<Document> fused = ReciprocalRankFusion.fuse(
        List.of(List.of(d1), List.of(d1)), 5, 60);
    assertEquals(1, fused.size());
  }

  @Test
  @DisplayName("topK 截断生效")
  void testTopKTruncate() {
    List<Document> fused = ReciprocalRankFusion.fuse(
        List.of(List.of(doc("a"), doc("b"), doc("c"))), 2, 60);
    assertEquals(2, fused.size());
  }

  @Test
  @DisplayName("空列表或 topK<=0 返回空")
  void testEmptyInputs() {
    assertTrue(ReciprocalRankFusion.fuse(List.of(), 5, 60).isEmpty());
    assertTrue(ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), 5, 60).isEmpty());
    assertTrue(ReciprocalRankFusion.fuse(List.of(List.of(doc("a"))), 0, 60).isEmpty());
  }

  @Test
  @DisplayName("融合分数写入 metadata 的 hybrid_score")
  void testScoreInMetadata() {
    List<Document> fused = ReciprocalRankFusion.fuse(
        List.of(List.of(doc("d1"))), 1, 60);
    assertEquals(1.0 / 61.0, (double) fused.get(0).getMetadata().get("hybrid_score"), 1e-9);
  }

  private Document doc(String id) {
    return new Document(id, "内容-" + id, Map.of());
  }
}
