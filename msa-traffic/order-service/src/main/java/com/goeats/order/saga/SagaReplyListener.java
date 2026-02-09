package com.goeats.order.saga;

import com.goeats.common.command.SagaReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Saga Reply Listener - Spring Cloud Stream 함수형 Consumer
 *
 * <p>saga-replies 토픽에서 SagaReply를 수신하여 SagaReplyHandler로 위임한다.
 * Payment/Delivery Service가 커맨드 처리 결과를 이 토픽으로 회신한다.</p>
 *
 * <h3>★ Choreography → Orchestration 변경</h3>
 * <pre>
 * Before (Choreography):
 *   handlePaymentCompleted → payment-events 토픽 구독
 *   handlePaymentFailed → payment-failed-events 토픽 구독
 *   → 2개 토픽, 2개 Consumer 빈
 *
 * After (Orchestration):
 *   handleSagaReply → saga-replies 토픽 구독
 *   → 1개 토픽, 1개 Consumer 빈 (stepName으로 라우팅)
 * </pre>
 *
 * <h3>바인딩 매핑</h3>
 * <pre>
 *   handleSagaReply-in-0 → destination: saga-replies, group: order-service
 * </pre>
 *
 * ★ Replaces PaymentEventListener (Choreography)
 *   Single Consumer for all saga step replies
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SagaReplyListener {

    private final SagaReplyHandler sagaReplyHandler;

    /**
     * Saga Reply Consumer 빈.
     *
     * <p>함수 빈 이름 "handleSagaReply"가 Spring Cloud Stream 바인딩과 매핑:
     * handleSagaReply-in-0 → saga-replies 토픽 구독.</p>
     */
    @Bean
    public Consumer<Message<SagaReply>> handleSagaReply() {
        return message -> {
            SagaReply reply = message.getPayload();
            sagaReplyHandler.handle(reply);
        };
    }
}
