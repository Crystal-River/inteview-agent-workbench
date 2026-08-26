package interview.guide.infrastructure.messaging;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RabbitMQ 消息门面。
 *
 * <p>封装队列声明、发布与监听容器构建，替代原先 Redis Stream 的消息路径。
 * 消息体为扁平 {@link Map}&lt;String,String&gt;，统一用 Jackson 3 序列化为 JSON 文本，
 * 避免默认类型头（__TypeId__）指向不可反序列化的不可变 Map。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskMessageBroker {

  private final RabbitTemplate rabbitTemplate;
  private final AmqpAdmin amqpAdmin;
  private final ConnectionFactory connectionFactory;
  private final ObjectMapper objectMapper;

  /**
   * 发布消息到指定队列（默认交换机，routing key = 队列名），使用默认队列最大长度。
   */
  public void publish(String queueName, Map<String, String> message) {
    publish(queueName, message, AsyncTaskStreamConstants.STREAM_MAX_LEN);
  }

  /**
   * 发布消息到指定队列，可指定队列最大长度（仅队列首次声明时生效）。
   */
  public void publish(String queueName, Map<String, String> message, int maxLength) {
    declareQueue(queueName, maxLength);
    String json;
    try {
      json = objectMapper.writeValueAsString(message);
    } catch (Exception e) {
      throw new IllegalStateException("序列化 RabbitMQ 消息失败: " + queueName, e);
    }
    rabbitTemplate.convertAndSend(queueName, json);
  }

  /**
   * 幂等声明 durable 队列，带 x-max-length 限制（溢出丢弃最旧消息）。
   *
   * <p>声明失败仅告警不抛出，避免 RabbitMQ 暂不可用时阻断应用启动或生产者入队；
   * 后续 publish 会再次尝试声明。</p>
   */
  public void declareQueue(String queueName) {
    declareQueue(queueName, AsyncTaskStreamConstants.STREAM_MAX_LEN);
  }

  /**
   * 幂等声明 durable 队列，自定义最大长度。
   */
  public void declareQueue(String queueName, int maxLength) {
    Queue queue = QueueBuilder.durable(queueName)
        .withArgument("x-max-length", maxLength)
        .withArgument("x-overflow", "drop-head")
        .build();
    try {
      amqpAdmin.declareQueue(queue);
    } catch (Exception e) {
      log.warn("声明 RabbitMQ 队列失败（稍后重试）: queue={}", queueName, e);
    }
  }

  /**
   * 构建未启动的监听容器。MANUAL ack + 单消费者 + prefetch 1，与原单线程逐条处理语义一致。
   */
  public SimpleMessageListenerContainer newListenerContainer(
      String queueName,
      String consumerTag,
      String threadName,
      ChannelAwareMessageListener listener) {
    return newListenerContainer(queueName, consumerTag, threadName, listener, 1,
        AsyncTaskStreamConstants.STREAM_MAX_LEN);
  }

  /**
   * 构建未启动的监听容器，支持指定并发消费者数量与队列最大长度。
   *
   * <p>并发消费者共享同一消费者标签前缀，按序号追加后缀保证唯一。</p>
   */
  public SimpleMessageListenerContainer newListenerContainer(
      String queueName,
      String consumerTag,
      String threadName,
      ChannelAwareMessageListener listener,
      int concurrency,
      int maxLength) {
    declareQueue(queueName, maxLength);
    SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
    container.setQueueNames(queueName);
    container.setAmqpAdmin(amqpAdmin);
    container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    container.setPrefetchCount(concurrency);
    container.setConcurrentConsumers(concurrency);
    container.setDefaultRequeueRejected(false);
    AtomicInteger tagIndex = new AtomicInteger();
    container.setConsumerTagStrategy(q -> consumerTag + "-" + tagIndex.getAndIncrement());
    container.setMessageListener(listener);
    container.setTaskExecutor(new SimpleAsyncTaskExecutor(threadName));
    return container;
  }

  /**
   * 反序列化消息体为扁平字符串 Map。
   */
  public Map<String, String> convert(Message message) {
    String json = new String(message.getBody(), StandardCharsets.UTF_8);
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
      });
    } catch (Exception e) {
      throw new IllegalStateException("反序列化 RabbitMQ 消息失败", e);
    }
  }
}
