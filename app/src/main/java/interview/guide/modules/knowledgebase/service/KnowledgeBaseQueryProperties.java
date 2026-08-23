package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private HybridSearch hybrid = new HybridSearch();
    private History history = new History();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
    }

    @Data
    public static class Search {
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }

    /**
     * 混合检索配置（BM25 + 向量 + RRF 融合）。
     * 前缀 {@code app.ai.rag.hybrid}。
     */
    @Data
    public static class HybridSearch {
        /** 混合检索总开关，关闭时退化为纯向量检索 */
        private boolean enabled = true;
        /** 融合前各路候选数 = topK * candidateMultiplier */
        private int candidateMultiplier = 3;
        /** BM25 词频饱和度参数 */
        private double bm25K1 = 1.2;
        /** BM25 文档长度归一化参数 */
        private double bm25B = 0.75;
        /** RRF 平滑常数 */
        private double rrfK = 60;
    }
}
