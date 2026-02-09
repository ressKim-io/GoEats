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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * 주문 이벤트 리스너 - Spring Cloud Stream 함수형 Consumer
 *
 * <p>order-events 토픽에서 OrderCreatedEvent를 소비하여 결제를 처리한다.
 * Spring Cloud Stream의 함수형 모델을 사용하여 브로커에 독립적인 구현.</p>
 *
 * <h3>★ Spring Cloud Stream 마이그레이션</h3>
 * <pre>
 * Before (Kafka 직접 의존):
 *   @Component
 *   @KafkaListener(topics = "order-events")
 *   @RetryableTopic(attempts = "4")
 *   public void handleOrderCreated(OrderCreatedEvent event) { ... }
 *
 * After (브로커 추상화):
 *   @Configuration
 *   @Bean
 *   public Consumer&lt;Message&lt;OrderCreatedEvent&gt;&gt; handleOrderCreated() {
 *       return message -> { ... };
 *   }
 * </pre>
 *
 * <h3>@RetryableTopic 대체</h3>
 * <p>Spring Cloud Stream의 바인더 레벨 재시도 + DLQ 설정으로 대체:</p>
 * <pre>
 *   spring.cloud.stream.bindings.handleOrderCreated-in-0.consumer:
 *     maxAttempts: 4
 *     backOffInitialInterval: 1000
 *     backOffMultiplier: 2.0
 *   spring.cloud.stream.kafka.bindings.handleOrderCreated-in-0.consumer:
 *     enableDlq: true
 *     dlqName: order-events.payment-service.dlq
 * </pre>
 *
 * <h3>트랜잭션 처리</h3>
 * <p>@Transactional은 함수형 빈에서 직접 사용할 수 없음 (Spring AOP 프록시 이슈).
 * 별도 @Service인 OrderEventHandler로 트랜잭션 로직을 위임.</p>
 *
 * <h3>적용된 트래픽 패턴 (3중 보호)</h3>
 * <ol>
 *   <li><b>재시도</b> - maxAttempts + 지수 백오프 (application.yml 설정)</li>
 *   <li><b>DLQ</b> - enableDlq: true로 영구 실패 메시지 격리</li>
 *   <li><b>Idempotent Consumer (멱등성)</b> - ProcessedEvent 테이블로 중복 방지</li>
 * </ol>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic: @KafkaListener + try/catch → 재시도 없음, 중복 처리 가능.
 * Traffic: 함수형 Consumer + 바인더 재시도 + DLQ + 멱등성 = 3중 보호.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic: 동기적 직접 호출 → 이벤트 리스너 불필요.
 * MSA: 비동기 이벤트 기반 통신 → 재시도/DLQ/멱등성 필수.</p>
 *
 * ★ Spring Cloud Stream functional Consumer for OrderCreatedEvent
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
public class OrderEventListener {

    private final OrderEventHandler orderEventHandler;

    /**
     * 주문 생성 이벤트 Consumer 빈.
     *
     * <p>함수 빈 이름 "handleOrderCreated"가 Spring Cloud Stream 바인딩과 매핑된다.
     * application.yml에서 handleOrderCreated-in-0 바인딩이 order-events 토픽을 구독.</p>
     *
     * <p>재시도/DLQ 설정은 application.yml로 이동 (코드에서 제거):</p>
     * <ul>
     *   <li>maxAttempts: 4 (최초 1회 + 재시도 3회)</li>
     *   <li>backOff: 1초 → 2초 → 4초 (지수 백오프)</li>
     *   <li>enableDlq: true (DLQ로 영구 실패 메시지 격리)</li>
     * </ul>
     */
    @Bean
    public Consumer<Message<OrderCreatedEvent>> handleOrderCreated() {
        return message -> {
            OrderCreatedEvent event = message.getPayload();
            orderEventHandler.handle(event);
        };
    }
}
