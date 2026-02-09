package com.goeats.order.service;

import com.goeats.order.dto.QueueStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Redis 주문 대기열 서비스 - Redis Sorted Set 기반 주문 큐
 *
 * <h3>시나리오</h3>
 * <p>피크타임(점심 11:30~13:30, 저녁 17:30~19:30)에 주문이 폭주하면
 * 시스템 과부하 방지를 위해 주문을 Redis 대기열에 넣고 순서대로 처리한다.
 * 티켓팅 대기열(인터파크, YES24)과 동일한 패턴.</p>
 *
 * <h3>왜 Redis Sorted Set인가?</h3>
 * <pre>
 * List (LPUSH/RPOP):
 *   - FIFO만 가능, 중간 조회 O(N)
 *   - 순번 조회(LPOS) 비효율적
 *
 * Sorted Set (ZADD/ZPOPMIN):
 *   - score = 타임스탬프 → 자연스러운 FIFO
 *   - ZRANK: 현재 순번 O(log N) ★ 핵심 장점
 *   - ZCARD: 대기열 크기 O(1)
 *   - 우선순위 큐로도 확장 가능 (VIP 주문 = 낮은 score)
 * </pre>
 *
 * <h3>Redis 명령어 매핑</h3>
 * <pre>
 *   enqueue()    → ZADD order:queue {timestamp} {orderId}
 *   dequeue()    → ZPOPMIN order:queue
 *   getPosition()→ ZRANK order:queue {orderId}
 *   getQueueSize → ZCARD order:queue
 * </pre>
 *
 * <h3>★ 메시징 3종 비교에서의 위치</h3>
 * <pre>
 *   Kafka (Spring Cloud Stream): 서비스 간 신뢰성 높은 비동기 통신 (영속)
 *   Redis Sorted Set:            피크타임 주문 대기열 (소비까지 영속) ← 이것
 *   Redis Pub/Sub:               실시간 알림 (fire-and-forget)
 * </pre>
 *
 * <h3>★ vs Kafka</h3>
 * <p>Kafka: 서비스 간 이벤트 전달 (OrderCreated → PaymentService).
 * Redis Queue: 같은 서비스 내 주문 처리 속도 제어 (throttling).
 * 서로 다른 목적이므로 공존한다.</p>
 *
 * ★ Redis Sorted Set order queue for peak-time traffic management
 *
 * Commands: ZADD (enqueue), ZPOPMIN (dequeue), ZRANK (position), ZCARD (size)
 * Score = timestamp → natural FIFO ordering
 * O(log N) position lookup vs O(N) for List
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueueService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String QUEUE_KEY = "order:queue";
    private static final String ACTIVE_ORDERS_KEY = "order:active-count";
    // Maximum concurrent orders being processed before queue activation
    private static final int MAX_ACTIVE_ORDERS = 50;
    // Estimated processing time per order in seconds
    private static final long PROCESSING_INTERVAL_SECONDS = 1;

    /**
     * 주문을 대기열에 추가 (ZADD).
     * score = 현재 타임스탬프(밀리초) → 자연스러운 FIFO 순서 보장.
     *
     * @param orderId 대기열에 추가할 주문 ID
     * @return 대기열 상태 응답 (현재 순번, 예상 대기 시간)
     */
    public QueueStatusResponse enqueue(Long orderId) {
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(QUEUE_KEY, orderId.toString(), score);
        log.info("Order enqueued: orderId={}, score={}", orderId, score);

        long position = getPosition(orderId);
        long queueSize = getQueueSize();

        return new QueueStatusResponse(
                orderId,
                position,
                position * PROCESSING_INTERVAL_SECONDS,
                queueSize
        );
    }

    /**
     * 대기열에서 가장 오래된 주문 꺼내기 (ZPOPMIN).
     * score가 가장 낮은(가장 먼저 들어온) 주문을 원자적으로 제거하고 반환.
     *
     * @return 꺼낸 주문 ID, 대기열이 비어있으면 null
     */
    public Long dequeue() {
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<Object>> result = zSetOps.popMin(QUEUE_KEY, 1);

        if (result == null || result.isEmpty()) {
            return null;
        }

        Object value = result.iterator().next().getValue();
        Long orderId = Long.valueOf(value.toString());
        log.debug("Order dequeued: orderId={}", orderId);
        return orderId;
    }

    /**
     * 현재 대기 순번 조회 (ZRANK).
     * 0-based: 0이면 다음 처리 대상, 1이면 한 명 앞에 있음.
     *
     * @param orderId 조회할 주문 ID
     * @return 대기 순번 (0-based), 대기열에 없으면 -1
     */
    public long getPosition(Long orderId) {
        Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, orderId.toString());
        return rank != null ? rank : -1;
    }

    /**
     * 전체 대기열 크기 (ZCARD).
     *
     * @return 현재 대기 중인 주문 수
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForZSet().zCard(QUEUE_KEY);
        return size != null ? size : 0;
    }

    /**
     * 대기열 활성화 여부 판단.
     *
     * <p>현재 처리 중인 주문 수가 MAX_ACTIVE_ORDERS를 초과하면 대기열을 활성화한다.
     * 이미 대기열에 주문이 있는 경우에도 활성화 상태를 유지한다.</p>
     *
     * @return 대기열이 활성화되었으면 true
     */
    public boolean isQueueActive() {
        // If there are already orders in the queue, keep it active
        if (getQueueSize() > 0) {
            return true;
        }
        // Check current active order count
        Object count = redisTemplate.opsForValue().get(ACTIVE_ORDERS_KEY);
        long activeOrders = count != null ? Long.parseLong(count.toString()) : 0;
        return activeOrders >= MAX_ACTIVE_ORDERS;
    }

    /**
     * 활성 주문 수 증가 (주문 처리 시작 시 호출).
     */
    public void incrementActiveOrders() {
        redisTemplate.opsForValue().increment(ACTIVE_ORDERS_KEY);
    }

    /**
     * 활성 주문 수 감소 (주문 처리 완료 시 호출).
     */
    public void decrementActiveOrders() {
        redisTemplate.opsForValue().decrement(ACTIVE_ORDERS_KEY);
    }

    /**
     * 대기열 상태 조회.
     *
     * @param orderId 조회할 주문 ID
     * @return 대기열 상태 응답 (순번, 예상 대기 시간, 전체 크기)
     */
    public QueueStatusResponse getQueueStatus(Long orderId) {
        long position = getPosition(orderId);
        long queueSize = getQueueSize();

        return new QueueStatusResponse(
                orderId,
                position,
                position >= 0 ? position * PROCESSING_INTERVAL_SECONDS : 0,
                queueSize
        );
    }
}
