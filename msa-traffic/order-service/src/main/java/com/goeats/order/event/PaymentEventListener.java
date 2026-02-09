package com.goeats.order.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * 결제 이벤트 리스너 - Spring Cloud Stream 함수형 Consumer
 *
 * <p>Payment 서비스에서 발행한 결제 완료/실패 이벤트를 수신하여
 * 주문 상태를 업데이트하고, Saga 상태를 진행시킨다.</p>
 *
 * <h3>★ Spring Cloud Stream 마이그레이션</h3>
 * <pre>
 * Before (Kafka 직접 의존):
 *   @KafkaListener(topics = "payment-events") + @RetryableTopic
 *   @KafkaListener(topics = "payment-failed-events") + @RetryableTopic
 *   @DltHandler
 *
 * After (브로커 추상화):
 *   Consumer&lt;Message&lt;PaymentCompletedEvent&gt;&gt; handlePaymentCompleted()
 *   Consumer&lt;Message&lt;PaymentFailedEvent&gt;&gt; handlePaymentFailed()
 *   → 재시도/DLQ는 application.yml 설정으로 이동
 * </pre>
 *
 * <h3>핵심 패턴 3가지</h3>
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
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic: @KafkaListener + try/catch → 재시도 없음, 중복 처리 가능.
 * Traffic: 함수형 Consumer + 바인더 재시도 + DLQ + 멱등성 = 3중 보호.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic: @Transactional에서 동기적 처리 → 이벤트 리스너 불필요.
 * MSA: 비동기 이벤트 기반 Saga 패턴 → 각 이벤트별 Consumer 필수.</p>
 *
 * ★ Spring Cloud Stream functional Consumer for Payment events
 *
 * vs Before: @KafkaListener + @RetryableTopic (Kafka-specific)
 * vs After:  Consumer<Message<T>> beans (broker-independent)
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
     * 결제 완료 이벤트 Consumer 빈.
     *
     * <p>함수 빈 이름 "handlePaymentCompleted"가 바인딩과 매핑:
     * handlePaymentCompleted-in-0 → payment-events 토픽 구독.</p>
     *
     * <p>결제 성공 시 주문 상태를 PAID로 변경, Saga 상태를 PAYMENT_COMPLETED로 진행.</p>
     */
    @Bean
    public Consumer<Message<PaymentCompletedEvent>> handlePaymentCompleted() {
        return message -> {
            PaymentCompletedEvent event = message.getPayload();
            paymentEventHandler.handleCompleted(event);
        };
    }

    /**
     * 결제 실패 이벤트 Consumer 빈.
     *
     * <p>함수 빈 이름 "handlePaymentFailed"가 바인딩과 매핑:
     * handlePaymentFailed-in-0 → payment-failed-events 토픽 구독.</p>
     *
     * <p>결제 실패 시 Saga 보상 트랜잭션 수행: 주문을 CANCELLED로 변경.</p>
     */
    @Bean
    public Consumer<Message<PaymentFailedEvent>> handlePaymentFailed() {
        return message -> {
            PaymentFailedEvent event = message.getPayload();
            paymentEventHandler.handleFailed(event);
        };
    }
}
