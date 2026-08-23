package interview.guide.modules.knowledgebase.retrieval;

import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 Spring AI Filter 表达式中提取 kb_id 过滤值（best-effort）。
 *
 * <p>项目构建的向量过滤表达式形如 {@code kb_id in ['1', '2']}；
 * 解析失败或遇到不支持的表达式时返回空列表，由调用方决定是否降级（不过滤搜索全部知识库）。
 */
final class KbFilterParser {

  private KbFilterParser() {
  }

  static List<Long> extractKnowledgeBaseIds(Filter.Expression expression) {
    if (expression == null) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    collect(expression, values);
    List<Long> ids = new ArrayList<>();
    for (String value : values) {
      try {
        ids.add(Long.parseLong(value));
      } catch (NumberFormatException ignored) {
        // 忽略无法解析的非数字值
      }
    }
    return ids;
  }

  private static void collect(Filter.Operand operand, List<String> out) {
    if (operand instanceof Filter.Expression expr) {
      if (expr.type() == Filter.ExpressionType.AND || expr.type() == Filter.ExpressionType.OR) {
        collect(expr.left(), out);
        collect(expr.right(), out);
      } else {
        collectOperation(expr, out);
      }
    } else if (operand instanceof Filter.Group group) {
      collect(group.content(), out);
    }
  }

  private static void collectOperation(Filter.Expression expr, List<String> out) {
    if (!(expr.left() instanceof Filter.Key key) || !"kb_id".equals(key.key())) {
      return;
    }
    Filter.Operand right = expr.right();
    if (right instanceof Filter.Value value) {
      collectValue(value.value(), out);
    } else if (right instanceof Filter.Group group) {
      collect(group.content(), out);
    }
  }

  private static void collectValue(Object value, List<String> out) {
    if (value instanceof List<?> list) {
      for (Object item : list) {
        collectScalar(item, out);
      }
    } else {
      collectScalar(value, out);
    }
  }

  private static void collectScalar(Object item, List<String> out) {
    if (item instanceof Filter.Value v) {
      out.add(String.valueOf(v.value()));
    } else if (item != null) {
      out.add(String.valueOf(item));
    }
  }
}
