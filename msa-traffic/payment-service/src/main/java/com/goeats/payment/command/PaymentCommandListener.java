package com.goeats.payment.command;

import com.goeats.common.command.PaymentCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Payment Command Listener - Spring Cloud Stream 함수형 Consumer
 *
 * <p>payment-commands 토픽에서 PaymentCommand를 수신하여 PaymentCommandHandler로 위임한다.
 * Order Orchestrator가 결제 처리(PROCESS) 또는 결제 보상(COMPENSATE)을 지시한다.</p>
 *
 * <h3>★ Choreography → Orchestration 변경</h3>
 * <pre>
 * Before (Choreography):
 *   handleOrderCreated → order-events 토픽 구독 (OrderCreatedEvent)
 *   Payment가 이벤트를 보고 스스로 결제 처리
 *
 * After (Orchestration):
 *   handlePaymentCommand → payment-commands 토픽 구독 (PaymentCommand)
 *   Orchestrator의 명시적 커맨드에 따라 PROCESS 또는 COMPENSATE 수행
 * </pre>
 *
 * <h3>바인딩 매핑</h3>
 * <pre>
 *   handlePaymentCommand-in-0 → destination: payment-commands, group: payment-service
 * </pre>
 *
 * ★ Replaces OrderEventListener (Choreography)
 *   Receives explicit commands instead of reacting to events
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentCommandListener {

    private final PaymentCommandHandler paymentCommandHandler;

    /**
     * Payment Command Consumer 빈.
     *
     * <p>함수 빈 이름 "handlePaymentCommand"가 Spring Cloud Stream 바인딩과 매핑:
     * handlePaymentCommand-in-0 → payment-commands 토픽 구독.</p>
     */
    @Bean
    public Consumer<Message<PaymentCommand>> handlePaymentCommand() {
        return message -> {
            PaymentCommand command = message.getPayload();
            paymentCommandHandler.handle(command);
        };
    }
}
