package interview.guide.common.async;

import com.rabbitmq.client.Channel;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.messaging.TaskMessageBroker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.util.Map;
import java.util.UUID;

/**
 * 异步任务消费者模板基类。
 *
 * <p>线程安全：channel / deliveryTag 均作为参数透传，不再保存为实例字段，
 * 因此单容器开启 {@code consumerConcurrency() > 1} 时不会产生竞态。</p>
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final TaskMessageBroker messageBroker;
    private String consumerName;
    private SimpleMessageListenerContainer container;

    protected AbstractStreamConsumer(TaskMessageBroker messageBroker) {
        this.messageBroker = messageBroker;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        this.container = messageBroker.newListenerContainer(
            streamKey(), consumerName, threadName(), this::onMessage,
            consumerConcurrency(), queueMaxLength());
        this.container.start();
        log.info("{} consumer started: queue={}, group={}, consumerName={}, concurrency={}",
            taskDisplayName(), streamKey(), groupName(), consumerName, consumerConcurrency());
    }

    @PreDestroy
    public void shutdown() {
        if (container != null) {
            container.stop();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    private void onMessage(Message message, Channel channel) {
        String messageId = String.valueOf(message.getMessageProperties().getDeliveryTag());
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            Map<String, String> data = messageBroker.convert(message);
            processMessage(messageId, data, channel, deliveryTag);
        } catch (Exception e) {
            log.error("{} 消息解析失败，ack 丢弃: messageId={}",
                taskDisplayName(), messageId, e);
            ackMessage(messageId, channel, deliveryTag);
        }
    }

    private void processMessage(String messageId, Map<String, String> data,
                                Channel channel, long deliveryTag) {
        T payload;
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e);
            ackMessage(messageId, channel, deliveryTag);
            return;
        }

        if (payload == null) {
            ackMessage(messageId, channel, deliveryTag);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            if (shouldSkip(payload)) {
                ackMessage(messageId, channel, deliveryTag);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            if (!tryMarkProcessing(payload)) {
                ackMessage(messageId, channel, deliveryTag);
                log.info("{} task was not claimed: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            processBusiness(payload);
            markCompleted(payload);
            ackMessage(messageId, channel, deliveryTag);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
            }
            ackMessage(messageId, channel, deliveryTag);
        }
    }

    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void ackMessage(String messageId, Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Failed to ack message: messageId={}", messageId, e);
        }
    }

    protected void republish(Map<String, String> message) {
        messageBroker.publish(streamKey(), message, queueMaxLength());
    }

    /**
     * 消费者并发数量（同一队列并发处理消息的消费者数），默认 1（逐条串行）。
     */
    protected int consumerConcurrency() {
        return 1;
    }

    /**
     * 队列最大长度（x-max-length），默认与普通任务队列一致；大文件 embedding 队列可覆盖。
     */
    protected int queueMaxLength() {
        return AsyncTaskStreamConstants.STREAM_MAX_LEN;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(String messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected boolean shouldSkip(T payload) {
        return false;
    }

    protected abstract void markProcessing(T payload);

    /**
     * 尝试领取任务。默认保持原有消费者的状态更新语义。
     */
    protected boolean tryMarkProcessing(T payload) {
        markProcessing(payload);
        return true;
    }

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);
}
