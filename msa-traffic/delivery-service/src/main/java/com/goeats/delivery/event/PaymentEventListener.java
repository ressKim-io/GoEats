package com.goeats.delivery.event;

import com.goeats.common.event.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * 결제 완료 이벤트 리스너 - Spring Cloud Stream 함수형 Consumer
 *
 * <p>결제 서비스가 Outbox → 브로커로 발행한 결제 완료 이벤트를 소비하여
 * 자동으로 배달 엔티티를 생성하고 라이더 매칭을 시작한다.</p>
 *
 * <h3>★ Spring Cloud Stream 마이그레이션</h3>
 * <pre>
 * Before (Kafka 직접 의존):
 *   @KafkaListener(topics = "payment-events", groupId = "delivery-service")
 *   @RetryableTopic(attempts = "4", backoff = ...)
 *   @Transactional
 *   public void handlePaymentCompleted(PaymentCompletedEvent event) { ... }
 *
 * After (브로커 추상화):
 *   @Bean
 *   Consumer&lt;Message&lt;PaymentCompletedEvent&gt;&gt; handlePaymentCompletedForDelivery()
 *   → 재시도/DLQ는 application.yml 설정으로 이동
 * </pre>
 *
 * <h3>적용된 패턴 3가지 (Traffic MSA 핵심!)</h3>
 * <ol>
 *   <li><b>재시도</b> - maxAttempts + 지수 백오프 (바인더 레벨 설정)</li>
 *   <li><b>DLQ</b> - enableDlq: true로 영구 실패 메시지 격리</li>
 *   <li><b>Idempotent Consumer</b> - ProcessedEvent 테이블로 중복 방지</li>
 * </ol>
 *
 * <h3>트랜잭션 처리</h3>
 * <p>@Transactional은 함수형 빈에서 직접 사용할 수 없음 (Spring AOP 프록시 이슈).
 * PaymentEventHandler @Service로 트랜잭션 로직을 위임.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식: 결제 완료 후 같은 트랜잭션 안에서 배달 생성 (@Transactional).
 * 실패 시 전체 롤백되므로 재시도/멱등성 패턴 불필요.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA: @KafkaListener + try/catch → 실패 시 이벤트 유실.
 * Traffic: 함수형 Consumer + 바인더 재시도 + DLQ + 멱등성 = 3중 보호.</p>
 *
 * ★ Spring Cloud Stream functional Consumer for PaymentCompletedEvent
 *
 * vs Before: @KafkaListener + @RetryableTopic (Kafka-specific)
 * vs After:  Consumer<Message<T>> bean (broker-independent)
 *
 * Retry/DLQ configuration moved to application.yml
 * → Switch from Kafka to GCP Pub/Sub with ZERO code changes
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentEventListener {

    private final PaymentEventHandler paymentEventHandler;

    /**
     * 결제 완료 이벤트 Consumer 빈 - 배달 생성.
     *
     * <p>함수 빈 이름 "handlePaymentCompletedForDelivery"가 바인딩과 매핑:
     * handlePaymentCompletedForDelivery-in-0 → payment-events 토픽 구독.</p>
     *
     * <p>order-service의 handlePaymentCompleted와 다른 consumer group으로
     * 동일 토픽(payment-events)의 메시지를 독립적으로 소비한다.</p>
     */
    @Bean
    public Consumer<Message<PaymentCompletedEvent>> handlePaymentCompletedForDelivery() {
        return message -> {
            PaymentCompletedEvent event = message.getPayload();
            paymentEventHandler.handle(event);
        };
    }
}
