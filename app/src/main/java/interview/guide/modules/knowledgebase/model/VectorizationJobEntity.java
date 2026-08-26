package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 大文件向量化任务 checkpoint 实体。
 *
 * <p>对应 {@code vectorization_jobs} 表，记录每次向量化尝试的解析/嵌入进度，
 * 用于失败重试与断点恢复。</p>
 */
@Entity
@Table(name = "vectorization_jobs", indexes = {
    @Index(name = "idx_vj_kb_id", columnList = "kbId"),
    @Index(name = "idx_vj_status", columnList = "status")
})
public class VectorizationJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "job_id", nullable = false, unique = true, length = 36)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private VectorizationStatus status = VectorizationStatus.PARSING;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks = 0;

    @Column(name = "emitted_chunks", nullable = false)
    private Integer emittedChunks = 0;

    @Column(nullable = false)
    private Integer remaining = 0;

    @Column(length = 500)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public VectorizationStatus getStatus() {
        return status;
    }

    public void setStatus(VectorizationStatus status) {
        this.status = status;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
    }

    public Integer getEmittedChunks() {
        return emittedChunks;
    }

    public void setEmittedChunks(Integer emittedChunks) {
        this.emittedChunks = emittedChunks;
    }

    public Integer getRemaining() {
        return remaining;
    }

    public void setRemaining(Integer remaining) {
        this.remaining = remaining;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
