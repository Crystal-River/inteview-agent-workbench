-- 知识库大文件向量化流水线
-- vectorization_jobs: 每次向量化尝试的 checkpoint（解析/嵌入进度、断点恢复）
-- vectorization_chunks: 解析产出的分块暂存表（embedding 阶段按索引消费）
CREATE TABLE IF NOT EXISTS vectorization_jobs (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    job_id VARCHAR(36) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PARSING',
    total_chunks INTEGER NOT NULL DEFAULT 0,
    emitted_chunks INTEGER NOT NULL DEFAULT 0,
    remaining INTEGER NOT NULL DEFAULT 0,
    error VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vj_kb_id ON vectorization_jobs (kb_id);
CREATE INDEX IF NOT EXISTS idx_vj_status ON vectorization_jobs (status);

CREATE TABLE IF NOT EXISTS vectorization_chunks (
    job_id VARCHAR(36) NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_id UUID NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_vc_job_status ON vectorization_chunks (job_id, status);
