package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     * 
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);
        
        /* 
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        
        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);
            
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }
            
            return deletedRows;
            
        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 抛出异常以触发事务回滚
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    /**
     * 删除指定向量化任务写入的临时向量数据。
     */
    public int deleteByVectorJobId(String jobId) {
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int deletedRows = jdbcTemplate.update(sql, jobId);
            log.info("已清理临时向量数据: jobId={}, 删除行数={}", jobId, deletedRows);
            return deletedRows;
        } catch (Exception e) {
            log.error("清理临时向量数据失败: jobId={}, error={}", jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "清理临时向量数据失败");
        }
    }

    /**
     * 将临时向量任务提升为当前知识库的正式向量数据。
     */
    public int promoteVectorJob(Long knowledgeBaseId, String jobId) {
        String sql = """
            UPDATE vector_store
            SET metadata = (jsonb_set(
                    metadata::jsonb,
                    '{kb_id}',
                    to_jsonb(?::text),
                    true
                ) - 'kb_vector_job_id' - 'kb_target_id')::json
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int updatedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), jobId);
            log.info("临时向量数据已提升为正式数据: kbId={}, jobId={}, 更新行数={}",
                knowledgeBaseId, jobId, updatedRows);
            return updatedRows;
        } catch (Exception e) {
            log.error("提升临时向量数据失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "提升临时向量数据失败");
        }
    }

    /**
     * 按知识库 ID 列表读取向量分块（供 BM25 关键词检索使用）。
     * <p>
     * 空列表视为不过滤（与向量搜索"空=搜全部"语义一致）。
     *
     * @param knowledgeBaseIds 知识库 ID 列表，可为空
     * @return 分块列表；查询失败时返回空列表（检索侧走降级，不抛异常）
     */
    public List<VectorChunk> findChunksByKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        String sql;
        Object[] args;
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            sql = """
                SELECT id::text AS id, content, metadata->>'kb_id' AS kb_id
                FROM vector_store
                """;
            args = new Object[0];
        } else {
            String placeholders = String.join(",", knowledgeBaseIds.stream().map(id -> "?").toList());
            sql = """
                SELECT id::text AS id, content, metadata->>'kb_id' AS kb_id
                FROM vector_store
                WHERE metadata->>'kb_id' IN (%s)
                """.formatted(placeholders);
            args = knowledgeBaseIds.stream().map(String::valueOf).toArray();
        }
        try {
            List<VectorChunk> chunks = jdbcTemplate.query(
                sql, (rs, rowNum) -> new VectorChunk(
                    rs.getString("id"),
                    rs.getString("content"),
                    rs.getString("kb_id")
                ), args);
            log.debug("读取向量分块成功: kbIds={}, chunks={}", knowledgeBaseIds, chunks.size());
            return chunks;
        } catch (Exception e) {
            log.warn("读取向量分块失败，BM25 检索将降级: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 重建 HNSW 向量索引（批量写入完成后一次性调用，避免频繁重建）。
     */
    public void rebuildIndex() {
        String sql = "REINDEX INDEX spring_ai_vector_index";
        try {
            jdbcTemplate.execute(sql);
            log.info("向量索引重建完成: spring_ai_vector_index");
        } catch (Exception e) {
            log.error("重建向量索引失败: error={}", e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "重建向量索引失败");
        }
    }

    /**
     * 向量分块记录（用于 BM25 检索）。
     *
     * @param id      chunk 的 UUID 字符串（与向量搜索返回的 Document id 一致，用于 RRF 去重）
     * @param content chunk 文本
     * @param kbId    所属知识库 ID 字符串
     */
    public record VectorChunk(String id, String content, String kbId) {
    }
}
