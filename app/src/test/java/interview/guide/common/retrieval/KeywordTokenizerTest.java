package interview.guide.common.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("关键词分词器测试")
class KeywordTokenizerTest {

  @Test
  @DisplayName("纯中文按 bigram 切分")
  void testChineseBigram() {
    assertEquals(List.of("操作", "作系", "系统"), KeywordTokenizer.tokenize("操作系统"));
  }

  @Test
  @DisplayName("纯英文按单词小写切分")
  void testEnglishWords() {
    assertEquals(List.of("redis", "stream"), KeywordTokenizer.tokenize("Redis Stream"));
  }

  @Test
  @DisplayName("中英混合切分")
  void testMixedChineseEnglish() {
    assertEquals(List.of("redis", "消息", "息积", "积压"), KeywordTokenizer.tokenize("Redis 消息积压"));
  }

  @Test
  @DisplayName("标点与空白作为分隔符")
  void testPunctuationSeparator() {
    assertEquals(List.of("java", "开发", "经验"), KeywordTokenizer.tokenize("Java、开发.经验!"));
  }

  @Test
  @DisplayName("单个中文字作为 token")
  void testSingleCjkChar() {
    assertEquals(List.of("法"), KeywordTokenizer.tokenize("法"));
  }

  @Test
  @DisplayName("数字与字母按规则切分")
  void testAlphanumeric() {
    assertEquals(List.of("spring", "ai", "2", "0", "0"), KeywordTokenizer.tokenize("Spring AI 2.0.0"));
  }

  @Test
  @DisplayName("空串与 null 返回空列表")
  void testEmptyAndNull() {
    assertTrue(KeywordTokenizer.tokenize("").isEmpty());
    assertTrue(KeywordTokenizer.tokenize(null).isEmpty());
  }
}
