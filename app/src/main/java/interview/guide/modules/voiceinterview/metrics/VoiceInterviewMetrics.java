package interview.guide.modules.voiceinterview.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 语音面试指标记录器。
 *
 * <p>封装 Micrometer Timer / Counter 的调用，惰性获取 MeterRegistry，
 * 无注册表时静默跳过。</p>
 */
@Component
@RequiredArgsConstructor
public class VoiceInterviewMetrics {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        MeterRegistry registry = registry();
        if (registry == null) {
            return;
        }
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        registry.timer(metricName, tags).record(elapsed, TimeUnit.NANOSECONDS);
    }

    public void recordTimerMillis(String metricName, long millis, String... tags) {
        MeterRegistry registry = registry();
        if (registry == null) {
            return;
        }
        registry.timer(metricName, tags).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    public void incrementCounter(String metricName, String... tags) {
        MeterRegistry registry = registry();
        if (registry == null) {
            return;
        }
        registry.counter(metricName, tags).increment();
    }

    private MeterRegistry registry() {
        return meterRegistryProvider.getIfAvailable();
    }
}
