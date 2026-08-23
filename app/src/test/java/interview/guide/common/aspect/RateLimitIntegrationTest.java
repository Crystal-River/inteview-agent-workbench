package interview.guide.common.aspect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流功能集成测试
 *
 * <p>需要 Redis 服务运行。
 *
 * <p>运行方式：
 * <pre>
 * # 启动 Redis
 * docker run -d -p 6379:6379 redis:alpine
 *
 * # 取消 @Disabled 注解后运行
 * ./gradlew test --tests "RateLimitIntegrationTest"
 * </pre>
 */
@DisplayName("限流功能集成测试（需要 Redis）")
@Disabled
class RateLimitIntegrationTest {

    private static final String REDIS_ADDRESS = "redis://localhost:6379";

    private RedissonClient redissonClient;
    private String luaScript;
    private String luaScriptSha;

    @BeforeEach
    void setUp() throws Exception {
        ClassPathResource resource = new ClassPathResource("scripts/rate_limit_single.lua");
        luaScript = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);

        Config config = new Config();
        config.useSingleServer()
                .setAddress(REDIS_ADDRESS)
                .setDatabase(1)
                .setConnectionPoolSize(5)
                .setConnectionMinimumIdleSize(1);

        redissonClient = Redisson.create(config);
        redissonClient.getKeys().deleteByPattern("ratelimit:test*");

        // 预加载脚本
        luaScriptSha = redissonClient.getScript(StringCodec.INSTANCE).scriptLoad(luaScript);
    }

    @Test
    @DisplayName("验证限流：令牌充足时允许，耗尽时拒绝")
    void testRateLimit() {
        String key = "ratelimit:test:basic";
        long maxCount = 2;

        // 初始化2个令牌
        redissonClient.getBucket(key + ":value", StringCodec.INSTANCE).set(String.valueOf(maxCount));

        // 前两次请求应成功
        assertEquals(1L, executeLuaScript(key, maxCount));
        assertEquals(1L, executeLuaScript(key, maxCount));

        // 第三次请求应被拒绝（脚本返回 -i 标识第 i 条规则拒绝，单规则即 -1）
        assertEquals(-1L, executeLuaScript(key, maxCount));
    }

    @Test
    @DisplayName("验证多规则限流：任一规则不足时不扣减其他规则")
    void testMultiRule() {
        String globalKey = "ratelimit:test:multi:global";
        String ipKey = "ratelimit:test:multi:ip";
        long globalMax = 10;
        long ipMax = 1;

        // 初始化：全局10个令牌，IP维度1个令牌
        redissonClient.getBucket(globalKey + ":value", StringCodec.INSTANCE).set("10");
        redissonClient.getBucket(ipKey + ":value", StringCodec.INSTANCE).set("1");

        // 第一次请求：两条规则都通过
        assertEquals(1L, executeLuaScript(List.of(globalKey, ipKey), List.of(globalMax, ipMax)));

        // 第二次请求：IP规则拒绝，全局规则不能被误扣
        assertEquals(-2L, executeLuaScript(List.of(globalKey, ipKey), List.of(globalMax, ipMax)));
        assertEquals("9", redissonClient.getBucket(globalKey + ":value", StringCodec.INSTANCE).get());
        assertEquals("0", redissonClient.getBucket(ipKey + ":value", StringCodec.INSTANCE).get());
    }

    @Test
    @DisplayName("验证独立计数：不同维度拥有独立的令牌池")
    void testIndependentCountPerDimension() {
        String globalKey = "ratelimit:test:independent:global";
        String ipKey = "ratelimit:test:independent:ip";

        // 全局只允许2次，IP允许5次
        redissonClient.getBucket(globalKey + ":value", StringCodec.INSTANCE).set("2");
        redissonClient.getBucket(ipKey + ":value", StringCodec.INSTANCE).set("5");

        // 全局维度耗尽
        assertEquals(1L, executeLuaScript(globalKey, 2));
        assertEquals(1L, executeLuaScript(globalKey, 2));
        assertEquals(-1L, executeLuaScript(globalKey, 2));

        // IP维度仍有令牌（证明独立计数）
        assertEquals(1L, executeLuaScript(ipKey, 5));
    }

    @Test
    @DisplayName("回归：后续维度拒绝时，不得提前删除前面维度已过期的记录（防止令牌泄漏）")
    void testExpiredRecordsNotDeletedWhenLaterRuleRejects() {
        String globalKey = "ratelimit:test:leak:global";
        String ipKey = "ratelimit:test:leak:ip";
        long globalMax = 10;
        long ipMax = 1;
        long now = System.currentTimeMillis();

        // 全局维度 value 已耗尽为 0，但 zset 里有一条 5 秒前过期的记录（价值 3 个令牌）
        redissonClient.getBucket(globalKey + ":value", StringCodec.INSTANCE).set("0");
        redissonClient.getScoredSortedSet(globalKey + ":permits", StringCodec.INSTANCE)
            .add(now - 5000, "expired-req:1:3");

        // IP 维度 value 为 0，直接触发拒绝
        redissonClient.getBucket(ipKey + ":value", StringCodec.INSTANCE).set("0");

        // 全局维度本可通过（有 3 个过期令牌可回收），但 IP 维度拒绝 → 整体拒绝
        assertEquals(-2L, executeLuaScript(List.of(globalKey, ipKey), List.of(globalMax, ipMax)));

        // 关键断言：全局维度 value 不变，且过期记录仍保留、未被提前删除
        assertEquals("0", redissonClient.getBucket(globalKey + ":value", StringCodec.INSTANCE).get());
        assertTrue(
            redissonClient.getScoredSortedSet(globalKey + ":permits", StringCodec.INSTANCE)
                .readAll().contains("expired-req:1:3"),
            "拒绝时不应删除已过期的记录，否则其令牌无法回收造成泄漏");
    }

    private long executeLuaScript(String key, long maxCount) {
        return executeLuaScript(Collections.singletonList(key), Collections.singletonList(maxCount));
    }

    private long executeLuaScript(List<String> keys, List<Long> maxCounts) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);

        List<Object> args = new ArrayList<>(3 + keys.size() * 3);
        args.add(String.valueOf(System.currentTimeMillis()));
        args.add(UUID.randomUUID().toString());
        args.add(String.valueOf(keys.size()));

        for (Long maxCount : maxCounts) {
            args.add(String.valueOf(1));
            args.add(String.valueOf(1000));
            args.add(String.valueOf(maxCount));
        }

        Object result = script.evalSha(
                RScript.Mode.READ_WRITE,
                luaScriptSha,
                RScript.ReturnType.VALUE,
                new ArrayList<>(keys),
                args.toArray()
        );

        if (result instanceof Number) {
            return ((Number) result).longValue();
        } else if (result instanceof String) {
            return Long.parseLong((String) result);
        }
        throw new AssertionError("Unexpected result type: " + result);
    }

    @AfterEach
    void tearDown() {
        if (redissonClient != null) {
            redissonClient.getKeys().deleteByPattern("ratelimit:test*");
            redissonClient.shutdown();
        }
    }
}
