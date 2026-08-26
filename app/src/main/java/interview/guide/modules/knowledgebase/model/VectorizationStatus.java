package interview.guide.modules.knowledgebase.model;

/**
 * 大文件向量化任务状态。
 *
 * <p>PARSING（流式解析分块）→ EMBEDDING（并发向量化）→ FINALIZING（重建索引/切换向量）→
 * COMPLETED / FAILED。</p>
 */
public enum VectorizationStatus {
    PARSING,
    EMBEDDING,
    FINALIZING,
    COMPLETED,
    FAILED
}
