package com.goeats.order.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 주문 상태 발행자 - 실시간 알림용
 *
 * <h3>시나리오</h3>
 * <p>주문 상태가 변경될 때마다(CREATED → PAID → DELIVERING → DELIVERED)
 * Redis Pub/Sub 채널로 즉시 알림을 발행한다.
 * 구독자(WebSocket/SSE 서버)가 이를 받아 클라이언트에게 실시간으로 전달.</p>
 *
 * <h3>★ 메시징 3종 비교에서의 위치</h3>
 * <pre>
 *   Kafka (Spring Cloud Stream):
 *     - 용도: 서비스 간 신뢰성 높은 비동기 통신 (Saga 이벤트)
 *     - 영속성: O (로그 기반, 재처리 가능)
 *     - 예시: OrderCreated → PaymentService
 *
 *   Redis Sorted Set:
 *     - 용도: 피크타임 주문 대기열 (처리 속도 제어)
 *     - 영속성: O (소비까지)
 *     - 예시: ZADD/ZPOPMIN으로 FIFO 큐
 *
 *   Redis Pub/Sub: ← 이것
 *     - 용도: 실시간 알림 (주문 상태 변경 즉시 전파)
 *     - 영속성: X (fire-and-forget)
 *     - 예시: "주문이 배달 중입니다" 실시간 알림
 * </pre>
 *
 * <h3>왜 Kafka가 아닌 Redis Pub/Sub인가?</h3>
 * <ul>
 *   <li>실시간 알림은 "즉시 전달"이 목적 → 영속성 불필요</li>
 *   <li>구독자가 없으면 메시지 버림 (fire-and-forget) → 가벼움</li>
 *   <li>Kafka보다 지연 시간이 낮음 (ms 단위 vs 수십ms)</li>
 *   <li>별도 클러스터 불필요 (이미 Redis 사용 중)</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic: ApplicationEventPublisher로 JVM 내 이벤트 → WebSocket 직접 전송.
 * MSA: 서비스가 분리되어 있으므로 Redis Pub/Sub으로 서비스 간 실시간 전파.</p>
 *
 * ★ Redis Pub/Sub publisher for real-time order status notifications
 *
 * Channel: "order:status"
 * Pattern: fire-and-forget (no persistence, no retry)
 * Use case: Push notifications to clients via WebSocket/SSE
 *
 * vs Kafka: Kafka is for reliable async communication (Saga events)
 *           Redis Pub/Sub is for real-time notifications (no durability needed)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusPublisher {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CHANNEL = "order:status";

    /**
     * 주문 상태 변경 알림을 Redis 채널로 발행.
     *
     * <p>RedisTemplate.convertAndSend()는 Redis PUBLISH 명령어를 사용하여
     * "order:status" 채널의 모든 구독자에게 메시지를 즉시 전달한다.</p>
     *
     * <p>구독자가 없으면 메시지는 단순히 버려진다 (fire-and-forget).
     * 이것이 Kafka와의 핵심 차이: Kafka는 구독자 없어도 메시지를 보존한다.</p>
     *
     * @param orderId  상태가 변경된 주문 ID
     * @param status   새로운 주문 상태 (PAYMENT_PENDING, PAID, DELIVERING, DELIVERED 등)
     */
    public void publish(Long orderId, String status) {
        String message = String.format("{\"orderId\":%d,\"status\":\"%s\",\"timestamp\":%d}",
                orderId, status, System.currentTimeMillis());

        redisTemplate.convertAndSend(CHANNEL, message);
        log.debug("Order status published to Redis Pub/Sub: orderId={}, status={}", orderId, status);
    }
}
