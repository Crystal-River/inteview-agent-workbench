package interview.guide.modules.knowledgebase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库大文件向量化流水线配置。
 *
 * <p>前缀 {@code app.knowledgebase.pipeline}，控制大文件流式解析/分块/向量化的阈值与并发。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.knowledgebase.pipeline")
public class KnowledgeBasePipelineProperties {

    /**
     * 大文件阈值（字节）。小于等于该值走原有小文件链路，大于该值走流式流水线。
     */
    private long largeFileThreshold = 5 * 1024 * 1024L;

    /**
     * 每个 embedding 批次包含的 chunk 数（对齐 DashScope 批量上限）。
     */
    private int embedBatchSize = 10;

    /**
     * embedding 并发消费者数量。
     */
    private int embeddingConcurrency = 4;

    /**
     * 流式解析时正文分段 flush 阈值（字符），避免一次性把整份文本读入内存。
     */
    private int parseFlushSize = 64 * 1024;

    /**
     * 流式分块器缓冲阈值（字符），达到该值后切块输出，控制内存峰值。
     */
    private int chunkBufferSize = 64 * 1024;

    private Recovery recovery = new Recovery();

    @Data
    public static class Recovery {
        /**
         * PARSING 阶段卡死判定阈值（分钟）。
         */
        private long parsingStaleMinutes = 20;

        /**
         * EMBEDDING 阶段卡死判定阈值（分钟）。
         */
        private long embeddingStaleMinutes = 20;

        /**
         * 恢复调度扫描间隔（毫秒）。
         */
        private long fixedDelayMs = 60_000;
    }
}
