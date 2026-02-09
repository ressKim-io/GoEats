package com.goeats.order.scheduler;

import com.goeats.order.service.OrderQueueService;
import com.goeats.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주문 대기열 프로세서 - Redis Sorted Set 대기열에서 주문을 꺼내 처리하는 스케줄러
 *
 * <h3>동작 흐름</h3>
 * <pre>
 *   매 500ms마다 실행:
 *   1. OrderQueueService.dequeue()로 대기열에서 가장 오래된 주문 꺼내기 (ZPOPMIN)
 *   2. 주문이 있으면 OrderService.processQueuedOrder()로 처리
 *   3. 주문이 없으면 대기 (대기열이 비어있음)
 * </pre>
 *
 * <h3>ShedLock 적용</h3>
 * <p>다중 인스턴스 배포 시 한 인스턴스만 대기열을 처리하도록 ShedLock으로 락을 건다.
 * 이는 OutboxRelay와 동일한 패턴.</p>
 *
 * <h3>처리 속도 제어</h3>
 * <p>fixedDelay = 500ms → 초당 최대 2개 주문 처리.
 * 시스템 용량에 맞게 조정 가능.
 * 너무 빠르면 대기열의 의미가 없고, 너무 느리면 사용자 경험이 나빠진다.</p>
 *
 * <h3>★ 메시징 3종에서의 역할</h3>
 * <p>Redis Queue는 "같은 서비스 내 처리 속도 조절"에 사용된다.
 * Kafka의 consumer lag와 다르게, 명시적으로 대기열 크기와 순번을 관리할 수 있다.</p>
 *
 * ★ Scheduled processor for Redis order queue
 *   Dequeues orders at controlled rate (500ms interval)
 *   ShedLock prevents duplicate processing across instances
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueueProcessor {

    private final OrderQueueService orderQueueService;
    private final OrderService orderService;

    /**
     * 대기열에서 주문을 꺼내 처리하는 스케줄러.
     *
     * <p>500ms 간격으로 실행되며, ShedLock으로 다중 인스턴스 중 하나만 실행한다.
     * 대기열이 비어있으면 아무 작업 없이 종료한다.</p>
     */
    @Scheduled(fixedDelay = 500)
    @SchedulerLock(name = "OrderQueueProcessor", lockAtMostFor = "10s", lockAtLeastFor = "1s")
    public void processQueue() {
        Long orderId = orderQueueService.dequeue();

        if (orderId == null) {
            return; // Queue is empty
        }

        try {
            log.info("Processing queued order: orderId={}", orderId);
            orderService.processQueuedOrder(orderId);
            log.info("Queued order processed: orderId={}", orderId);
        } catch (Exception e) {
            log.error("Failed to process queued order: orderId={}", orderId, e);
            // Re-enqueue on failure for retry
            orderQueueService.enqueue(orderId);
        }
    }
}
