package com.goeats.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 릴레이 - Polling Publisher (Transactional Outbox - Relay)
 *
 * <p>Outbox 테이블에서 미발행 이벤트를 주기적으로 조회하여 Kafka로 전송하는 스케줄러.
 * Transactional Outbox 패턴의 두 번째 단계(발행)를 담당.</p>
 *
 * <h3>동작 흐름</h3>
 * <pre>
 *   매 1초마다 실행:
 *   1. outbox_events 테이블에서 published=false인 이벤트를 FIFO 순서로 조회
 *   2. 각 이벤트의 eventType을 기반으로 Kafka 토픽명 결정
 *   3. KafkaTemplate으로 메시지 전송 (key=aggregateId, value=payload)
 *   4. 전송 성공 시 markPublished()로 published=true 업데이트
 *   5. 전송 실패 시 break하여 순서 보장 (뒤의 이벤트를 먼저 보내지 않음)
 * </pre>
 *
 * <h3>토픽 네이밍 규칙</h3>
 * <pre>
 *   eventType         → Kafka Topic
 *   "OrderCreated"    → "order-events"
 *   "PaymentCompleted"→ "payment-events"
 *   "PaymentFailed"   → "payment-failed-events"
 *   "DeliveryStatus"  → "delivery-events"
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 Kafka 자체를 사용하지 않으므로 Relay가 불필요.
 * ApplicationEventPublisher가 같은 JVM 내에서 동기적으로 이벤트 전달.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 비즈니스 로직에서 KafkaTemplate.send()를 직접 호출.
 * MSA-Traffic에서는 Outbox Relay가 이벤트 발행을 전담하며:</p>
 * <ul>
 *   <li><b>이벤트 유실 방지</b>: DB에 저장된 이벤트는 반드시 Kafka로 전달됨</li>
 *   <li><b>순서 보장</b>: createdAt ASC + 실패 시 break로 이벤트 순서 유지</li>
 *   <li><b>다중 인스턴스 대응</b>: ShedLock으로 동시 실행 방지 (프로덕션 환경)</li>
 * </ul>
 *
 * <h3>주의: 다중 인스턴스 배포 시</h3>
 * <p>여러 인스턴스가 같은 Outbox 테이블을 폴링하면 중복 발행 가능.
 * 프로덕션에서는 ShedLock(@SchedulerLock)을 추가하여 한 인스턴스만 실행하도록 해야 함.</p>
 *
 * ★ Transactional Outbox - Relay (Polling Publisher)
 *
 * Polls the outbox table every second for unpublished events
 * and sends them to the corresponding Kafka topic.
 *
 * Flow:
 *   1. Query unpublished events (FIFO order)
 *   2. Send each event to Kafka topic (derived from eventType)
 *   3. Mark as published within a transaction
 *
 * Topic naming convention:
 *   - "OrderCreated"  → "order-events"
 *   - "PaymentCompleted" → "payment-events"
 *   - "PaymentFailed" → "payment-failed-events"
 *   - "DeliveryStatus" → "delivery-events"
 *
 * Note: In multi-instance deployments, use ShedLock or similar
 *       to prevent duplicate relay execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository; // 미발행 이벤트 조회용 리포지토리
    private final KafkaTemplate<String, String> kafkaTemplate; // Kafka 메시지 전송 클라이언트

    /**
     * 미발행 이벤트를 Kafka로 전송하는 스케줄러.
     * 1초(1000ms) 간격으로 실행. @Transactional로 발행 상태 업데이트의 원자성 보장.
     *
     * 순서 보장 전략: 하나라도 실패하면 break하여 뒤의 이벤트를 건너뛰지 않음.
     * 이는 "주문 생성 → 결제 완료" 순서가 뒤바뀌는 것을 방지.
     */
    @Scheduled(fixedDelay = 1000) // 이전 실행 완료 후 1초 뒤에 다시 실행
    @SchedulerLock(name = "OutboxRelay_publishPendingEvents",
            lockAtMostFor = "30s", lockAtLeastFor = "5s")
    @Transactional // Kafka 전송 성공 후 markPublished() 업데이트를 같은 트랜잭션으로 묶음
    public void publishPendingEvents() {
        // 1단계: 미발행 이벤트 FIFO 조회
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

        for (OutboxEvent event : pendingEvents) {
            try {
                // 2단계: eventType → Kafka 토픽명 변환
                String topic = resolveTopicName(event.getEventType());
                // 3단계: Kafka 전송 (key=aggregateId로 같은 주문의 이벤트는 같은 파티션에 전달)
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
                // 4단계: 발행 완료 표시
                event.markPublished();
                log.debug("Outbox relay published: topic={}, aggregateId={}",
                        topic, event.getAggregateId());
            } catch (Exception e) {
                log.error("Outbox relay failed: eventId={}, type={}",
                        event.getId(), event.getEventType(), e);
                break; // Stop processing to maintain order (순서 보장을 위해 중단)
            }
        }
    }

    /**
     * 이벤트 유형을 Kafka 토픽명으로 변환.
     * 규칙: 도메인 이벤트명 → 소문자 하이픈 구분 토픽명.
     */
    private String resolveTopicName(String eventType) {
        return switch (eventType) {
            case "OrderCreated" -> "order-events";
            case "PaymentCompleted" -> "payment-events";
            case "PaymentFailed" -> "payment-failed-events";
            case "DeliveryStatus" -> "delivery-events";
            default -> "unknown-events"; // 알 수 없는 이벤트 유형 (모니터링 필요)
        };
    }
}
