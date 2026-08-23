package interview.guide.common.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关键词分词器，用于 BM25 关键词检索。
 *
 * <p>规则：
 * <ul>
 *   <li>连续拉丁字母/数字 → 小写 word token（如 {@code Redis Stream} → {@code [redis, stream]}）</li>
 *   <li>连续 CJK 字符（U+3400–U+9FFF）→ 滑动窗口字符 bigram（如 {@code 操作系统} → {@code [操作, 作系, 系统]}），
 *       避免引入外部中文分词依赖，bigram 对专有名词/术语匹配足够稳定</li>
 *   <li>标点、空白等其余字符 → 分隔符</li>
 * </ul>
 */
public final class KeywordTokenizer {

  private KeywordTokenizer() {
  }

  /**
   * 将文本切分为关键词 token 列表。
   *
   * @param text 原始文本，允许为 null / 空串
   * @return token 列表（null/空串返回空列表）
   */
  public static List<String> tokenize(String text) {
    List<String> tokens = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return tokens;
    }
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (isCjk(c)) {
        int start = i;
        while (i < n && isCjk(text.charAt(i))) {
          i++;
        }
        addCjkTokens(tokens, text, start, i);
      } else if (Character.isLetterOrDigit(c)) {
        int start = i;
        while (i < n && Character.isLetterOrDigit(text.charAt(i))) {
          i++;
        }
        tokens.add(text.substring(start, i).toLowerCase(Locale.ROOT));
      } else {
        i++;
      }
    }
    return tokens;
  }

  private static boolean isCjk(char c) {
    return (c >= '\u3400' && c <= '\u4DBF') || (c >= '\u4E00' && c <= '\u9FFF');
  }

  private static void addCjkTokens(List<String> tokens, String text, int start, int end) {
    if (end - start >= 2) {
      for (int i = start; i + 1 < end; i++) {
        tokens.add(text.substring(i, i + 2));
      }
    } else {
      tokens.add(text.substring(start, end));
    }
  }
}
