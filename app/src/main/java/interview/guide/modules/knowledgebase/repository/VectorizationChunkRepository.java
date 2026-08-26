package interview.guide.modules.knowledgebase.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 大文件向量化分块暂存 Repository。
 *
 * <p>分块以 (job_id, chunk_index) 为主键幂等写入，chunk_id 由 job_id + chunk_index
 * 确定性派生（UUID v3），保证重复解析产生稳定 ID，配合 vector_store 的 upsert 实现幂等。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorizationChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 幂等批量写入分块（ON CONFLICT DO NOTHING），重复解析不产生重复行。
     *
     * @param jobId       任务ID
     * @param startIndex  起始 chunk_index（全局递增）
     * @param contents    分块文本
     * @return 本次实际新插入的行数（用于推进 job 进度，保证重试不重复计数）
     */
    public int insertChunks(String jobId, int startIndex, List<String> contents) {
        if (contents.isEmpty()) {
            return 0;
        }
        String sql = """
            INSERT INTO vectorization_chunks (job_id, chunk_index, chunk_id, content, status)
            VALUES (?, ?, ?, ?, 'PENDING')
            ON CONFLICT (job_id, chunk_index) DO NOTHING
            """;
        List<Object[]> batchArgs = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            int chunkIndex = startIndex + i;
            UUID chunkId = UUID.nameUUIDFromBytes(
                (jobId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8));
            batchArgs.add(new Object[]{jobId, chunkIndex, chunkId, contents.get(i)});
        }
        int[] results = jdbcTemplate.batchUpdate(sql, batchArgs);
        int inserted = 0;
        for (int r : results) {
            // PostgreSQL 对 ON CONFLICT DO NOTHING 返回 1（插入）/0（跳过）
            if (r > 0) {
                inserted++;
            }
        }
        return inserted;
    }

    /**
     * 读取指定索引区间内尚未嵌入的分块（用于并发 embedding 幂等消费）。
     */
    public List<VectorizationChunk> findPendingChunks(String jobId, int start, int count) {
        String sql = """
            SELECT chunk_index, chunk_id::text AS chunk_id, content
            FROM vectorization_chunks
            WHERE job_id = ? AND chunk_index >= ? AND chunk_index < ? AND status = 'PENDING'
            ORDER BY chunk_index
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorizationChunk(
            rs.getInt("chunk_index"),
            rs.getString("chunk_id"),
            rs.getString("content")
        ), jobId, start, start + count);
    }

    /**
     * 将指定索引区间内的 PENDING 分块标记为 EMBEDDED。
     *
     * @return 实际新标记的行数（用于原子递减 remaining，避免重复消息重复扣减）
     */
    public int markChunksEmbedded(String jobId, int start, int count) {
        String sql = """
            UPDATE vectorization_chunks
            SET status = 'EMBEDDED'
            WHERE job_id = ? AND chunk_index >= ? AND chunk_index < ? AND status = 'PENDING'
            """;
        return jdbcTemplate.update(sql, jobId, start, start + count);
    }

    /**
     * 列出所有 PENDING 分块索引（用于恢复调度器重发未完成批次）。
     */
    public List<Integer> findPendingChunkIndexes(String jobId) {
        String sql = """
            SELECT chunk_index FROM vectorization_chunks
            WHERE job_id = ? AND status = 'PENDING'
            ORDER BY chunk_index
            """;
        return jdbcTemplate.queryForList(sql, Integer.class, jobId);
    }

    /**
     * 分块暂存记录。
     *
     * @param chunkIndex 分块全局索引
     * @param chunkId    确定性 UUID（与 vector_store.id 一致，用于 upsert 幂等）
     * @param content    分块文本
     */
    public record VectorizationChunk(int chunkIndex, String chunkId, String content) {
    }
}
