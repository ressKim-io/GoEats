package com.goeats.order.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import com.goeats.order.repository.SagaStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 이벤트 리스너 - Kafka 기반 비동기 이벤트 처리 (DLQ + 멱등성)
 *
 * <h3>역할</h3>
 * Payment 서비스에서 발행한 결제 완료/실패 이벤트를 수신하여
 * 주문 상태를 업데이트하고, Saga 상태를 진행시킨다.
 *
 * <h3>핵심 패턴 3가지</h3>
 *
 * <h4>1. @RetryableTopic - 자동 재시도 + DLQ(Dead Letter Queue)</h4>
 * <pre>
 * 이벤트 처리 실패 시 지수 백오프(Exponential Backoff)로 자동 재시도:
 *   payment-events (원본)
 *     → payment-events-retry-0 (1초 후)
 *     → payment-events-retry-1 (2초 후)
 *     → payment-events-retry-2 (4초 후)
 *     → payment-events-dlt (최종 실패: Dead Letter Topic으로 이동)
 * </pre>
 *
 * <h4>2. @DltHandler - Dead Letter Topic 처리</h4>
 * 모든 재시도 실패 후 DLT로 이동한 메시지를 로깅한다.
 * 운영팀이 수동으로 확인하고 재처리할 수 있도록 기록한다.
 *
 * <h4>3. Idempotent Consumer - 멱등성 보장</h4>
 * <pre>
 * Kafka는 at-least-once 전달을 보장 → 동일 이벤트가 중복 수신될 수 있음.
 * ProcessedEvent 테이블에 처리된 eventId를 기록하여 중복 처리를 방지한다.
 * 흐름: eventId 존재 확인 → 없으면 처리 + eventId 저장 (같은 트랜잭션)
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서는 단순 @KafkaListener만 사용했다:
 * - 처리 실패 시 메시지 유실 (재시도 없음)
 * - 중복 이벤트 처리로 주문 상태가 꼬일 수 있음
 * - 영구 실패 메시지에 대한 처리 방안 없음
 *
 * MSA-Traffic에서는:
 * - @RetryableTopic으로 자동 재시도 + DLQ 처리
 * - ProcessedEvent로 멱등성 보장 (중복 이벤트 무시)
 * - @DltHandler로 영구 실패 메시지 로깅/알림
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 @Transactional에서 주문+결제를 동기적으로 처리하므로
 * 이벤트 리스너 자체가 불필요했다.
 * MSA에서는 서비스 간 비동기 통신을 위해 Kafka 이벤트 리스너가 필수다.
 *
 * ★★★ Traffic MSA: Enhanced Kafka Listener with DLQ
 *
 * vs Basic MSA:
 *   @KafkaListener → message lost on processing failure
 *
 * Traffic MSA:
 *   @RetryableTopic → automatic retry with exponential backoff
 *   @DltHandler → dead letter topic for permanently failed messages
 *   ProcessedEvent → idempotent consumption (skip duplicates)
 *
 * Retry flow:
 *   payment-events → payment-events-retry-0 → payment-events-retry-1
 *   → payment-events-retry-2 → payment-events-dlt (dead letter)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaStateRepository;
    private final ProcessedEventRepository processedEventRepository;  // 멱등성 보장용 저장소

    /**
     * 결제 완료 이벤트 처리
     *
     * @RetryableTopic 설정:
     * - attempts=4: 최초 1회 + 재시도 3회 = 총 4회 시도
     * - backoff: 1초 시작, 2배씩 증가 (1s → 2s → 4s)
     * - SUFFIX_WITH_INDEX_VALUE: 재시도 토픽명에 인덱스 붙임 (retry-0, retry-1, ...)
     * - ALWAYS_RETRY_ON_ERROR: 모든 예외에 대해 재시도 수행
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.ALWAYS_RETRY_ON_ERROR
    )
    @KafkaListener(topics = "payment-events", groupId = "order-service")
    @Transactional  // DB 작업(주문 상태 변경 + ProcessedEvent 저장)을 하나의 트랜잭션으로 묶음
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        // ★ Idempotent check: skip if already processed
        // ★ 멱등성 체크: 이미 처리된 이벤트인지 확인 (중복 수신 방지)
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentCompletedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        // 주문 상태를 PAID로 업데이트
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.PAID);
            log.info("Order {} updated to PAID", order.getId());
        });

        // ★ Update saga state
        // ★ Saga 상태 진행: PAYMENT_COMPLETED 단계로 전진
        sagaStateRepository.findByOrderId(event.orderId())
                .ifPresent(saga -> {
                    saga.advanceStep("PAYMENT_COMPLETED");
                    log.info("Saga advanced to PAYMENT_COMPLETED for orderId={}", event.orderId());
                });

        // ★ Mark event as processed (idempotent consumer)
        // ★ 처리 완료 기록: 같은 트랜잭션에서 eventId를 저장하여 멱등성 보장
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /**
     * 결제 실패 이벤트 처리 (Saga 보상 트랜잭션)
     *
     * 결제가 실패하면 주문을 취소(CANCELLED)하고,
     * Saga 상태를 COMPENSATING으로 전환한다.
     * 이것이 Saga 패턴의 "보상 트랜잭션(Compensation)"이다.
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
    )
    @KafkaListener(topics = "payment-failed-events", groupId = "order-service")
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        // 멱등성 체크: 이미 처리된 이벤트면 건너뜀
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentFailedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        // ★ Saga compensation: cancel order on payment failure
        // ★ Saga 보상: 결제 실패 시 주문을 CANCELLED로 변경 (롤백 효과)
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.CANCELLED);
            log.warn("Order {} CANCELLED due to payment failure: {}",
                    order.getId(), event.reason());
        });

        // Saga 상태를 COMPENSATING으로 전환하고 실패 사유 기록
        sagaStateRepository.findByOrderId(event.orderId())
                .ifPresent(saga -> saga.startCompensation(event.reason()));

        // 처리 완료 기록 (멱등성)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /**
     * Dead Letter Topic 핸들러 - 모든 재시도 실패 후 최종 처리
     *
     * 4회 시도(원본 + 재시도 3회) 후에도 실패한 메시지가 여기로 전달된다.
     * 운영팀에게 알림을 보내거나, 별도 저장소에 기록하여 수동 재처리를 유도한다.
     *
     * 실무에서는:
     * - Slack/PagerDuty 알림 연동
     * - DLT 전용 DB 테이블에 저장
     * - 관리자 UI에서 재처리 버튼 제공
     *
     * ★ Dead Letter Topic handler: log permanently failed messages for manual review
     */
    @DltHandler
    public void handleDlt(Object event) {
        log.error("Message sent to DLT (dead letter topic). Manual intervention required: {}", event);
    }
}
