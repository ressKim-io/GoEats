package com.goeats.delivery.command;

import com.goeats.common.command.DeliveryCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Delivery Command Listener - Spring Cloud Stream 함수형 Consumer
 *
 * <p>delivery-commands 토픽에서 DeliveryCommand를 수신하여 DeliveryCommandHandler로 위임한다.
 * Order Orchestrator가 결제 성공 후 배달 생성을 지시한다.</p>
 *
 * <h3>★ Choreography → Orchestration 변경</h3>
 * <pre>
 * Before (Choreography):
 *   handlePaymentCompletedForDelivery → payment-events 토픽 구독
 *   Delivery가 PaymentCompletedEvent를 보고 스스로 배달 생성
 *
 * After (Orchestration):
 *   handleDeliveryCommand → delivery-commands 토픽 구독
 *   Orchestrator의 명시적 커맨드에 따라 배달 생성
 * </pre>
 *
 * <h3>바인딩 매핑</h3>
 * <pre>
 *   handleDeliveryCommand-in-0 → destination: delivery-commands, group: delivery-service
 * </pre>
 *
 * ★ Replaces PaymentEventListener (Choreography)
 *   Receives explicit command instead of reacting to payment event
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DeliveryCommandListener {

    private final DeliveryCommandHandler deliveryCommandHandler;

    /**
     * Delivery Command Consumer 빈.
     *
     * <p>함수 빈 이름 "handleDeliveryCommand"가 Spring Cloud Stream 바인딩과 매핑:
     * handleDeliveryCommand-in-0 → delivery-commands 토픽 구독.</p>
     */
    @Bean
    public Consumer<Message<DeliveryCommand>> handleDeliveryCommand() {
        return message -> {
            DeliveryCommand command = message.getPayload();
            deliveryCommandHandler.handle(command);
        };
    }
}
