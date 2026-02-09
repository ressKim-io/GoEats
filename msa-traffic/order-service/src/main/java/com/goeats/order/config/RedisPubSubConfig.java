package com.goeats.order.config;

import com.goeats.order.event.OrderStatusSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 설정 - 실시간 주문 상태 알림 채널 구성
 *
 * <h3>구성 요소</h3>
 * <ul>
 *   <li><b>RedisMessageListenerContainer</b>: Redis SUBSCRIBE 관리 컨테이너</li>
 *   <li><b>ChannelTopic("order:status")</b>: 구독할 채널명</li>
 *   <li><b>OrderStatusSubscriber</b>: 메시지 수신 시 호출되는 핸들러</li>
 * </ul>
 *
 * <h3>동작 원리</h3>
 * <pre>
 *   Publisher (OrderStatusPublisher):
 *     redisTemplate.convertAndSend("order:status", message)
 *       → Redis PUBLISH order:status message
 *
 *   Subscriber (이 설정으로 등록):
 *     RedisMessageListenerContainer
 *       → Redis SUBSCRIBE order:status
 *       → 메시지 수신 시 OrderStatusSubscriber.onMessage() 호출
 * </pre>
 *
 * <h3>★ vs Kafka Consumer</h3>
 * <p>Kafka Consumer: consumer group으로 메시지 분배 (한 메시지 = 한 소비자).
 * Redis Pub/Sub: 모든 구독자가 같은 메시지를 수신 (broadcast).
 * 실시간 알림은 broadcast가 적합 → Redis Pub/Sub 선택.</p>
 *
 * ★ Redis Pub/Sub configuration for order status notifications
 *
 * Channel: "order:status"
 * Subscriber: OrderStatusSubscriber
 * Pattern: broadcast to all subscribers (vs Kafka: consumer group based)
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * Redis 메시지 리스너 컨테이너 빈.
     *
     * <p>"order:status" 채널을 구독하고, 메시지 수신 시
     * OrderStatusSubscriber.onMessage()를 호출한다.</p>
     *
     * @param connectionFactory Redis 연결 팩토리 (application.yml의 spring.data.redis로 자동 구성)
     * @param subscriber        메시지 수신 핸들러
     * @return 구성된 리스너 컨테이너
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            OrderStatusSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic("order:status"));
        return container;
    }
}
