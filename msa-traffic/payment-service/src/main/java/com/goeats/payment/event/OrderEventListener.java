package com.goeats.payment.event;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.common.outbox.OutboxService;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.entity.PaymentStatus;
import com.goeats.payment.service.PaymentService;
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
 * 주문 이벤트 리스너 - Kafka에서 주문 생성 이벤트를 수신하여 결제를 처리한다.
 *
 * <p>이 클래스는 Payment Service의 핵심 이벤트 처리기로, order-events 토픽에서
 * OrderCreatedEvent를 소비하여 결제를 생성하고, 결과를 Outbox 패턴으로 발행한다.</p>
 *
 * <h3>적용된 트래픽 패턴 (3중 보호)</h3>
 * <ol>
 *   <li><b>@RetryableTopic (재시도)</b> - 실패 시 최대 4회 자동 재시도.
 *       지수 백오프(1초 -> 2초 -> 4초)로 재시도 간격을 점진적으로 늘린다.
 *       재시도 토픽: order-events-retry-0, order-events-retry-1, order-events-retry-2</li>
 *   <li><b>@DltHandler (Dead Letter Topic)</b> - 모든 재시도 실패 후 DLT로 이동.
 *       운영자가 수동으로 확인하고 처리할 수 있도록 로그를 남긴다.</li>
 *   <li><b>Idempotent Consumer (멱등성)</b> - ProcessedEvent 테이블로 이미 처리한 이벤트를
 *       추적하여, 재시도나 중복 전달 시 같은 이벤트를 두 번 처리하지 않도록 방지한다.</li>
 * </ol>
 *
 * <h3>이벤트 처리 흐름</h3>
 * <pre>
 *   Kafka(order-events)
 *     → 멱등성 체크 (ProcessedEvent 존재?)
 *     → PaymentService.processPayment() 호출
 *     → 결과에 따라 Outbox에 PaymentCompleted/PaymentFailed 이벤트 저장
 *     → ProcessedEvent 테이블에 처리 완료 기록
 *     → OutboxRelay가 @Scheduled로 Outbox → Kafka 발행
 * </pre>
 *
 * <h3>★★★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 @KafkaListener + try/catch만 사용했다.
 * 이 경우 처리 실패 시 메시지가 유실되거나, 재시도 시 중복 처리되는 문제가 있었다.
 * Traffic에서는 재시도(@RetryableTopic) + DLQ(@DltHandler) + 멱등성(ProcessedEvent) 3중 보호로
 * 메시지 유실과 중복 처리를 모두 방지한다.</p>
 *
 * <h3>★★★ vs Monolithic</h3>
 * <p>Monolithic에서는 OrderService가 PaymentService를 직접 호출(@Transactional)하므로
 * 실패 시 전체 트랜잭션이 롤백되어 이런 패턴이 필요 없었다.
 * MSA에서는 네트워크 분리로 인해 "메시지 전달 보장"이라는 새로운 과제가 생기며,
 * 이를 해결하기 위해 재시도, DLQ, 멱등성이라는 3가지 패턴이 필수적이다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final PaymentService paymentService;
    private final OutboxService outboxService;  // Outbox 패턴 - DB에 이벤트를 원자적으로 저장
    private final ProcessedEventRepository processedEventRepository;  // 멱등성 체크용 저장소

    /**
     * 주문 생성 이벤트 핸들러.
     *
     * <p>@RetryableTopic: 실패 시 최대 4회 재시도 (원본 1회 + 재시도 3회).
     * 지수 백오프로 1초 -> 2초 -> 4초 간격으로 재시도한다.
     * SUFFIX_WITH_INDEX_VALUE 전략으로 재시도 토픽명에 인덱스를 붙인다
     * (예: order-events-retry-0, order-events-retry-1).</p>
     *
     * @param event 주문 생성 이벤트 (orderId, totalAmount, paymentMethod, eventId 포함)
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.ALWAYS_RETRY_ON_ERROR
    )
    @KafkaListener(topics = "order-events", groupId = "payment-service")
    @Transactional  // 결제 처리 + ProcessedEvent 저장 + Outbox 저장을 하나의 트랜잭션으로 묶음
    public void handleOrderCreated(OrderCreatedEvent event) {
        // ★ Idempotent check - 이미 처리한 이벤트인지 확인 (중복 방지)
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;  // 이미 처리됨 → 스킵 (멱등성 보장)
        }

        log.info("Processing OrderCreatedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        try {
            // 결제 처리 (PaymentService의 이중 멱등성 체크 포함)
            Payment payment = paymentService.processPayment(
                    event.orderId(), event.totalAmount(),
                    event.paymentMethod(), event.eventId());

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                // ★ Outbox: atomic payment result publishing
                // 결제 성공 이벤트를 Outbox 테이블에 저장 (같은 트랜잭션 내에서 원자적으로)
                PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                        payment.getId(), event.orderId(),
                        event.totalAmount(), event.paymentMethod());

                // OutboxService가 outbox 테이블에 INSERT → 나중에 OutboxRelay가 Kafka로 발행
                outboxService.saveEvent("Payment", payment.getId().toString(),
                        "PaymentCompleted", completedEvent);

                log.info("Payment completed, outbox event saved: orderId={}", event.orderId());
            } else {
                // 결제 실패 이벤트를 Outbox에 저장 → Saga 보상 트랜잭션 트리거
                PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                        event.orderId(), "Payment processing failed");

                outboxService.saveEvent("Payment", event.orderId().toString(),
                        "PaymentFailed", failedEvent);

                log.warn("Payment failed, outbox event saved: orderId={}", event.orderId());
            }
        } catch (Exception e) {
            log.error("Payment processing error: orderId={}", event.orderId(), e);
            // 예외 발생 시에도 실패 이벤트를 Outbox에 저장하여 Saga 보상 가능
            PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                    event.orderId(), e.getMessage());
            outboxService.saveEvent("Payment", event.orderId().toString(),
                    "PaymentFailed", failedEvent);
        }

        // ★ Mark as processed - 이벤트 처리 완료 기록 (다음에 같은 이벤트가 오면 스킵됨)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /**
     * Dead Letter Topic 핸들러.
     *
     * <p>@RetryableTopic의 모든 재시도가 실패한 후 DLT(Dead Letter Topic)로 이동된 메시지를 처리한다.
     * 자동 복구가 불가능한 상황이므로, 운영자 수동 개입이 필요하다는 것을 로그로 알린다.</p>
     *
     * <p>실무에서는 Slack/PagerDuty 알림, 관리자 대시보드 기록 등의 후속 처리를 추가한다.</p>
     *
     * @param event 재시도 실패한 주문 생성 이벤트
     */
    @DltHandler
    public void handleDlt(OrderCreatedEvent event) {
        log.error("OrderCreatedEvent sent to DLT. Manual intervention required: orderId={}",
                event.orderId());
    }
}
