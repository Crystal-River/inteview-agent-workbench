package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.config.KnowledgeBasePipelineProperties;
import interview.guide.modules.knowledgebase.listener.ChunkingBatchProducer;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {

    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    private final VectorizationJobService vectorizationJobService;
    private final ChunkingBatchProducer chunkingBatchProducer;
    private final KnowledgeBasePipelineProperties pipelineProperties;

    private static final long MAX_FILE_SIZE = 1024 * 1024 * 1024; // 1GB
    
    /**
     * 上传知识库文件
     *
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        // 大文件走流式解析流水线，避免全量读入内存
        if (file.getSize() > pipelineProperties.getLargeFileThreshold()) {
            return uploadLargeKnowledgeBase(file, name, category);
        }

        // 3. 检查知识库是否已存在（去重）
        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 4. 解析知识库文本（用于向量化）
        String content = parseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }

        // 5. 保存文件到RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        // 6. 保存知识库元数据到数据库（状态为 PENDING）
        KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(file, name, category, fileKey, fileUrl, fileHash);

        // 7. 发送向量化任务到 Redis Stream（异步处理）
        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), content);

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}", fileName, savedKb.getId());

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                "fileSize", savedKb.getFileSize(),
                "contentLength", content.length(),
                "vectorStatus", VectorStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false
        );
    }

    /**
     * 大文件上传：流式算 hash 去重、流式上传 RustFS、建 PARSING 任务后异步分块。
     *
     * <p>不把文件内容一次性读入内存，向量化走「分块 → 并发 embedding → 完成后 REINDEX」流水线。</p>
     */
    private Map<String, Object> uploadLargeKnowledgeBase(MultipartFile file, String name, String category) {
        // 流式计算哈希去重
        String fileHash;
        try (InputStream input = file.getInputStream()) {
            fileHash = fileHashService.calculateHash(input);
        } catch (IOException e) {
            log.error("读取文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 流式上传到 RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("大文件知识库已存储到 RustFS: {}", fileKey);

        // 保存元数据（PENDING）+ 创建向量化任务（PARSING）
        KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(
            file, name, category, fileKey, fileUrl, fileHash);
        String jobId = vectorizationJobService.createJob(savedKb.getId());

        // 发送分块任务（消息只携带 kbId/jobId）
        if (!chunkingBatchProducer.sendChunkingTask(savedKb.getId(), jobId)) {
            vectorizationJobService.finishFailed(jobId, "分块任务入队失败");
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量化任务入队失败");
        }

        log.info("大文件知识库上传完成，分块任务已入队: {}, kbId={}, jobId={}",
            file.getOriginalFilename(), savedKb.getId(), jobId);

        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                "fileSize", savedKb.getFileSize(),
                "contentLength", 0,
                "vectorStatus", VectorStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false
        );
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        );
    }
    
    /**
     * 重新向量化知识库（手动重试）
     * 从 RustFS 重新下载文件并发送向量化任务
     *
     * @param kbId 知识库ID
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.getName());

        // 大文件走流式流水线重建
        if (kb.getFileSize() != null && kb.getFileSize() > pipelineProperties.getLargeFileThreshold()) {
            revectorizeLarge(kb);
            return;
        }

        // 1. 下载文件并解析内容
        String content = parseService.downloadAndParseContent(kb.getStorageKey(), kb.getOriginalFilename());
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        // 2. 更新状态为 PENDING（通过单独的 Service 保证事务生效）
        persistenceService.updateVectorStatusToPending(kbId);

        // 3. 发送向量化任务到 Stream
        vectorizeStreamProducer.sendVectorizeTask(kbId, content);

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }

    /**
     * 大文件重新向量化：直接建 PARSING 任务并重新入队分块。
     */
    private void revectorizeLarge(KnowledgeBaseEntity kb) {
        persistenceService.updateVectorStatusToPending(kb.getId());

        String jobId = vectorizationJobService.createJob(kb.getId());
        if (!chunkingBatchProducer.sendChunkingTask(kb.getId(), jobId)) {
            vectorizationJobService.finishFailed(jobId, "分块任务入队失败");
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量化任务入队失败");
        }

        log.info("大文件重新向量化任务已发送: kbId={}, jobId={}", kb.getId(), jobId);
    }
}

