package interview.guide.common.constant;

/**
 * 异步任务 Redis Stream 通用常量
 * 包含知识库向量化和简历分析两个异步任务的配置
 */
public final class AsyncTaskStreamConstants {

    private AsyncTaskStreamConstants() {
        // 私有构造函数，防止实例化
    }

    // ========== 通用消息字段 ==========

    /**
     * 重试次数字段
     */
    public static final String FIELD_RETRY_COUNT = "retryCount";

    /**
     * 文档内容字段
     */
    public static final String FIELD_CONTENT = "content";

    // ========== 通用消费者配置 ==========

    /**
     * 最大重试次数
     */
    public static final int MAX_RETRY_COUNT = 3;

    /**
     * 队列最大长度（x-max-length，溢出丢弃最旧消息，防止无限增长）
     */
    public static final int STREAM_MAX_LEN = 1000;

    // ========== 知识库向量化 Stream 配置 ==========

    /**
     * 知识库向量化队列名
     */
    public static final String KB_VECTORIZE_STREAM_KEY = "knowledgebase.vectorize.queue";

    /**
     * 知识库向量化 Consumer Group 名称
     */
    public static final String KB_VECTORIZE_GROUP_NAME = "vectorize-group";

    /**
     * 知识库向量化 Consumer 名称前缀
     */
    public static final String KB_VECTORIZE_CONSUMER_PREFIX = "vectorize-consumer-";

    /**
     * 知识库ID字段
     */
    public static final String FIELD_KB_ID = "kbId";

    // ========== 简历分析 Stream 配置 ==========

    /**
     * 简历分析队列名
     */
    public static final String RESUME_ANALYZE_STREAM_KEY = "resume.analyze.queue";

    /**
     * 简历分析 Consumer Group 名称
     */
    public static final String RESUME_ANALYZE_GROUP_NAME = "analyze-group";

    /**
     * 简历分析 Consumer 名称前缀
     */
    public static final String RESUME_ANALYZE_CONSUMER_PREFIX = "analyze-consumer-";

    /**
     * 简历ID字段
     */
    public static final String FIELD_RESUME_ID = "resumeId";

    // ========== 面试评估 Stream 配置 ==========

    /**
     * 面试评估队列名
     */
    public static final String INTERVIEW_EVALUATE_STREAM_KEY = "interview.evaluate.queue";

    /**
     * 面试评估 Consumer Group 名称
     */
    public static final String INTERVIEW_EVALUATE_GROUP_NAME = "evaluate-group";

    /**
     * 面试评估 Consumer 名称前缀
     */
    public static final String INTERVIEW_EVALUATE_CONSUMER_PREFIX = "evaluate-consumer-";

    /**
     * 面试会话ID字段
     */
    public static final String FIELD_SESSION_ID = "sessionId";

    // ========== 语音面试评估 Stream 配置 ==========

    /**
     * 语音面试评估队列名
     */
    public static final String VOICE_EVALUATE_STREAM_KEY = "voice.evaluate.queue";

    /**
     * 语音面试评估 Consumer Group 名称
     */
    public static final String VOICE_EVALUATE_GROUP_NAME = "voice-evaluate-group";

    /**
     * 语音面试评估 Consumer 名称前缀
     */
    public static final String VOICE_EVALUATE_CONSUMER_PREFIX = "voice-evaluate-consumer-";

    /**
     * 语音面试会话ID字段
     */
    public static final String FIELD_VOICE_SESSION_ID = "voiceSessionId";

    // ========== 知识库问题生成 Stream 配置 ==========

    /**
     * 知识库问题生成队列名
     */
    public static final String KB_QUESTION_GEN_STREAM_KEY = "knowledgebase.question-gen.queue";

    /**
     * 知识库问题生成 Consumer Group 名称
     */
    public static final String KB_QUESTION_GEN_GROUP_NAME = "question-gen-group";

    /**
     * 知识库问题生成 Consumer 名称前缀
     */
    public static final String KB_QUESTION_GEN_CONSUMER_PREFIX = "question-gen-consumer-";

    /**
     * 任务ID字段（用于幂等判断）
     */
    public static final String FIELD_TASK_ID = "taskId";

    /**
     * 难度字段
     */
    public static final String FIELD_DIFFICULTY = "difficulty";

    /**
     * 题目数量字段
     */
    public static final String FIELD_QUESTION_COUNT = "questionCount";

    /**
     * 追问数量字段
     */
    public static final String FIELD_FOLLOW_UP_COUNT = "followUpCount";

    /**
     * 方向数量字段
     */
    public static final String FIELD_CATEGORY_LIMIT = "categoryLimit";

    /**
     * LLM Provider字段
     */
    public static final String FIELD_LLM_PROVIDER = "llmProvider";
}
