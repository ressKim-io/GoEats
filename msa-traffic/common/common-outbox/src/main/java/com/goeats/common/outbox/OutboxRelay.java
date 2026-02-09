package com.goeats.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 릴레이 - Polling Publisher (Transactional Outbox - Relay)
 *
 * <p>Outbox 테이블에서 미발행 이벤트를 주기적으로 조회하여 메시지 브로커로 전송하는 스케줄러.
 * Transactional Outbox 패턴의 두 번째 단계(발행)를 담당.</p>
 *
 * <h3>★ Spring Cloud Stream 추상화</h3>
 * <pre>
 * Before (Kafka 직접 의존):
 *   KafkaTemplate&lt;String, String&gt; kafkaTemplate;
 *   kafkaTemplate.send(topic, key, payload);
 *   → 브로커 교체 시 코드 수정 필수
 *
 * After (StreamBridge 추상화):
 *   StreamBridge streamBridge;
 *   streamBridge.send(bindingName, message);
 *   → 브로커 교체 시 application.yml의 binder만 변경 (코드 수정 0줄)
 * </pre>
 *
 * <h3>동작 흐름</h3>
 * <pre>
 *   매 1초마다 실행:
 *   1. outbox_events 테이블에서 published=false인 이벤트를 FIFO 순서로 조회
 *   2. 각 이벤트의 eventType을 기반으로 바인딩명 결정
 *   3. StreamBridge로 메시지 전송 (kafka_messageKey 헤더 = aggregateId)
 *   4. 전송 성공 시 markPublished()로 published=true 업데이트
 *   5. 전송 실패 시 break하여 순서 보장 (뒤의 이벤트를 먼저 보내지 않음)
 * </pre>
 *
 * <h3>바인딩 네이밍 규칙</h3>
 * <pre>
 *   eventType              → Binding Name
 *   "OrderCreated"         → "orderEvents-out-0"
 *   "PaymentCompleted"     → "paymentEvents-out-0"
 *   "PaymentFailed"        → "paymentFailedEvents-out-0"
 *   "DeliveryStatus"       → "deliveryEvents-out-0"
 *   "ProcessPayment"       → "paymentCommands-out-0"   (Orchestration)
 *   "CompensatePayment"    → "paymentCommands-out-0"   (Orchestration)
 *   "CreateDelivery"       → "deliveryCommands-out-0"  (Orchestration)
 *   "SagaReply"            → "sagaReplies-out-0"       (Orchestration)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 메시지 브로커 자체를 사용하지 않으므로 Relay가 불필요.
 * ApplicationEventPublisher가 같은 JVM 내에서 동기적으로 이벤트 전달.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 비즈니스 로직에서 KafkaTemplate.send()를 직접 호출.
 * MSA-Traffic에서는 Outbox Relay가 이벤트 발행을 전담하며:</p>
 * <ul>
 *   <li><b>이벤트 유실 방지</b>: DB에 저장된 이벤트는 반드시 브로커로 전달됨</li>
 *   <li><b>순서 보장</b>: createdAt ASC + 실패 시 break로 이벤트 순서 유지</li>
 *   <li><b>다중 인스턴스 대응</b>: ShedLock으로 동시 실행 방지</li>
 *   <li><b>브로커 독립</b>: StreamBridge로 Kafka/Pub/Sub 등 자유 교체 가능</li>
 * </ul>
 *
 * ★ Transactional Outbox - Relay (Polling Publisher)
 *
 * Polls the outbox table every second for unpublished events
 * and sends them to the corresponding binding via StreamBridge.
 *
 * Flow:
 *   1. Query unpublished events (FIFO order)
 *   2. Send each event via StreamBridge (binding derived from eventType)
 *   3. Mark as published within a transaction
 *
 * Binding naming convention:
 *   - "OrderCreated"        → "orderEvents-out-0"
 *   - "PaymentCompleted"    → "paymentEvents-out-0"
 *   - "PaymentFailed"       → "paymentFailedEvents-out-0"
 *   - "DeliveryStatus"      → "deliveryEvents-out-0"
 *   - "ProcessPayment"      → "paymentCommands-out-0"   (Orchestration)
 *   - "CompensatePayment"   → "paymentCommands-out-0"   (Orchestration)
 *   - "CreateDelivery"      → "deliveryCommands-out-0"  (Orchestration)
 *   - "SagaReply"           → "sagaReplies-out-0"       (Orchestration)
 *
 * ★ Broker independence: StreamBridge abstracts KafkaTemplate
 *   → Switch from Kafka to GCP Pub/Sub with ZERO code changes
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository; // 미발행 이벤트 조회용 리포지토리
    private final StreamBridge streamBridge; // ★ 브로커 추상화 메시지 전송 클라이언트

    /**
     * 미발행 이벤트를 메시지 브로커로 전송하는 스케줄러.
     * 1초(1000ms) 간격으로 실행. @Transactional로 발행 상태 업데이트의 원자성 보장.
     *
     * 순서 보장 전략: 하나라도 실패하면 break하여 뒤의 이벤트를 건너뛰지 않음.
     * 이는 "주문 생성 → 결제 완료" 순서가 뒤바뀌는 것을 방지.
     */
    @Scheduled(fixedDelay = 1000) // 이전 실행 완료 후 1초 뒤에 다시 실행
    @SchedulerLock(name = "OutboxRelay_publishPendingEvents",
            lockAtMostFor = "30s", lockAtLeastFor = "5s")
    @Transactional // 브로커 전송 성공 후 markPublished() 업데이트를 같은 트랜잭션으로 묶음
    public void publishPendingEvents() {
        // 1단계: 미발행 이벤트 FIFO 조회
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

        for (OutboxEvent event : pendingEvents) {
            try {
                // 2단계: eventType → 바인딩명 변환
                String binding = resolveBindingName(event.getEventType());

                // 3단계: StreamBridge로 메시지 전송
                // kafka_messageKey 헤더로 같은 주문의 이벤트가 같은 파티션에 전달됨
                Message<String> message = MessageBuilder
                        .withPayload(event.getPayload())
                        .setHeader("kafka_messageKey", event.getAggregateId())
                        .build();

                boolean sent = streamBridge.send(binding, message);
                if (!sent) {
                    log.error("StreamBridge send returned false: binding={}, aggregateId={}",
                            binding, event.getAggregateId());
                    break; // Stop processing to maintain order
                }

                // 4단계: 발행 완료 표시
                event.markPublished();
                log.debug("Outbox relay published: binding={}, aggregateId={}",
                        binding, event.getAggregateId());
            } catch (Exception e) {
                log.error("Outbox relay failed: eventId={}, type={}",
                        event.getId(), event.getEventType(), e);
                break; // Stop processing to maintain order (순서 보장을 위해 중단)
            }
        }
    }

    /**
     * 이벤트 유형을 Spring Cloud Stream 바인딩명으로 변환.
     * 규칙: 도메인 이벤트명 → camelCase 바인딩명 + "-out-0" 접미사.
     *
     * ★ vs Before: resolveTopicName() → Kafka 토픽명 직접 지정
     *    vs After:  resolveBindingName() → 추상 바인딩명 (실제 토픽은 yml에서 매핑)
     *
     * ★ Orchestration Saga additions:
     *    ProcessPayment, CompensatePayment → paymentCommands-out-0
     *    CreateDelivery → deliveryCommands-out-0
     *    SagaReply → sagaReplies-out-0
     */
    private String resolveBindingName(String eventType) {
        return switch (eventType) {
            // --- Choreography events (legacy, retained for reference) ---
            case "OrderCreated" -> "orderEvents-out-0";
            case "PaymentCompleted" -> "paymentEvents-out-0";
            case "PaymentFailed" -> "paymentFailedEvents-out-0";
            case "DeliveryStatus" -> "deliveryEvents-out-0";
            // --- Orchestration Saga commands/replies ---
            case "ProcessPayment", "CompensatePayment" -> "paymentCommands-out-0";
            case "CreateDelivery" -> "deliveryCommands-out-0";
            case "SagaReply" -> "sagaReplies-out-0";
            default -> "unknownEvents-out-0"; // Unknown event type (needs monitoring)
        };
    }
}
