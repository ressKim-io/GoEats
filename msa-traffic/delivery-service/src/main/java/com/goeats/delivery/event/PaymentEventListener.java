package com.goeats.delivery.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.delivery.service.DeliveryService;
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
 * 결제 완료 이벤트 리스너 - Kafka에서 PaymentCompletedEvent를 수신하여 배달을 생성한다.
 *
 * <p>결제 서비스가 Outbox → Kafka로 발행한 결제 완료 이벤트를 소비하여
 * 자동으로 배달 엔티티를 생성하고 라이더 매칭을 시작한다.</p>
 *
 * <h3>적용된 패턴 3가지 (Traffic MSA 핵심!)</h3>
 *
 * <h4>1. @RetryableTopic - 자동 재시도 + 리트라이 토픽</h4>
 * <p>이벤트 처리 실패 시 Spring Kafka가 자동으로 리트라이 토픽(payment-events-retry-0, -1, ...)으로
 * 이동시키며, 지수 백오프(1초 → 2초 → 4초)로 최대 4회 재시도한다.
 * 모든 재시도 실패 시 DLT(Dead Letter Topic)로 이동한다.</p>
 *
 * <h4>2. Idempotent Consumer (멱등 소비자)</h4>
 * <p>Kafka는 "at-least-once" 전달을 보장하므로 동일 이벤트가 중복 수신될 수 있다.
 * ProcessedEvent 테이블에 처리된 eventId를 기록하여, 이미 처리된 이벤트는 스킵한다.
 * 이를 통해 "exactly-once semantics"를 애플리케이션 레벨에서 구현한다.</p>
 *
 * <h4>3. @DltHandler - Dead Letter Topic 처리</h4>
 * <p>모든 재시도가 실패한 이벤트는 DLT로 이동하며, @DltHandler에서 로그를 남긴다.
 * 운영자가 DLT 이벤트를 모니터링하여 수동 처리할 수 있다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 결제 완료 후 같은 트랜잭션 안에서 배달을 생성한다 (@Transactional).
 * 실패 시 전체 롤백되므로 재시도/멱등성 패턴이 필요 없다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 @KafkaListener + try/catch로 단순 처리한다.
 * 실패 시 이벤트가 유실되거나 중복 처리될 수 있다.
 * Traffic 버전에서는 @RetryableTopic + Idempotent Consumer + @DltHandler로
 * 이벤트 유실 없이 정확히 한 번 처리를 보장한다.</p>
 *
 * ★★★ Traffic MSA: Enhanced Payment Event Listener for Delivery
 *
 * vs Basic MSA:
 *   @KafkaListener + try/catch → delivery creation lost on failure
 *
 * Traffic MSA:
 *   @RetryableTopic → auto retry failed delivery creation
 *   ProcessedEvent → skip duplicate PaymentCompletedEvents
 *   @DltHandler → log permanently failed events for manual review
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final DeliveryService deliveryService;
    private final ProcessedEventRepository processedEventRepository;  // 멱등성 체크용 저장소

    /**
     * 결제 완료 이벤트 처리 - 배달 생성.
     *
     * <p>@RetryableTopic 설정으로 실패 시 자동 재시도된다.</p>
     * <ul>
     *   <li>attempts = 4: 최초 1회 + 재시도 3회 = 총 4회 시도</li>
     *   <li>backoff: 1초 → 2초 → 4초 (지수 백오프)</li>
     *   <li>SUFFIX_WITH_INDEX_VALUE: 리트라이 토픽 이름에 인덱스 추가 (payment-events-retry-0, -1, ...)</li>
     *   <li>ALWAYS_RETRY_ON_ERROR: 모든 예외에 대해 재시도 실행</li>
     * </ul>
     */
    @RetryableTopic(
            attempts = "4",  // 최초 1회 + 재시도 3회
            backoff = @Backoff(delay = 1000, multiplier = 2.0),  // 1초 → 2초 → 4초 지수 백오프
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,  // 리트라이 토픽명: -retry-0, -retry-1
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.ALWAYS_RETRY_ON_ERROR  // 모든 에러에 대해 재시도
    )
    @KafkaListener(topics = "payment-events", groupId = "delivery-service")  // payment-events 토픽 구독
    @Transactional  // 멱등성 체크 + 배달 생성을 하나의 트랜잭션으로 묶음
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        // ★ Idempotent check: 이미 처리된 이벤트인지 확인 (중복 수신 방지)
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;  // 이미 처리된 이벤트 → 무시 (exactly-once 보장)
        }

        log.info("Processing PaymentCompletedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        try {
            // 배달 생성 + 라이더 매칭 시도
            deliveryService.createDelivery(event.orderId(), "Default Address");
            log.info("Delivery created for order: {}", event.orderId());
        } catch (Exception e) {
            log.error("Failed to create delivery for order: {}", event.orderId(), e);
            throw e; // Rethrow for @RetryableTopic to handle retry
            // 예외를 다시 던져서 @RetryableTopic이 리트라이 토픽으로 이동시키도록 함
        }

        // ★ Mark as processed: 처리 완료 기록 (다음 중복 수신 시 스킵하기 위해)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /**
     * DLT(Dead Letter Topic) 핸들러 - 모든 재시도가 실패한 이벤트 처리.
     *
     * <p>4회 시도(1초→2초→4초) 후에도 실패한 이벤트가 여기로 도달한다.
     * 운영자가 모니터링하여 수동으로 원인 파악 및 재처리해야 한다.</p>
     *
     * <p>실무에서는 알림(Slack, PagerDuty) 발송이나 관리 대시보드 기록을 추가한다.</p>
     */
    @DltHandler
    public void handleDlt(PaymentCompletedEvent event) {
        // 모든 재시도 실패 → 수동 개입 필요 (운영 알림 발송 권장)
        log.error("PaymentCompletedEvent sent to DLT. Manual intervention required: orderId={}",
                event.orderId());
    }
}
