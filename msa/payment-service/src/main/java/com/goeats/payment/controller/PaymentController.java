package com.goeats.payment.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 결제 조회 REST API 컨트롤러.
 *
 * <p>MSA에서 결제 "생성"은 REST API가 아닌 Kafka 이벤트로 트리거됩니다.
 * 이 컨트롤러는 결제 조회만 제공하며, 실제 결제 처리는
 * OrderEventListener가 Kafka 메시지를 수신하여 시작합니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: PaymentController에서 결제 생성/조회 모두 처리 가능
 * - MSA: 결제 생성은 이벤트 기반, 조회만 REST API로 노출
 * - 이렇게 하면 주문 서비스와 결제 서비스 간 동기 의존성이 사라짐</p>
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 주문 ID로 결제 정보를 조회합니다.
     * 다른 서비스(예: order-service)가 OpenFeign으로 이 API를 호출할 수 있습니다.
     */
    @GetMapping("/order/{orderId}")
    public ApiResponse<Payment> getPaymentByOrder(@PathVariable Long orderId) {
        return ApiResponse.ok(paymentService.getPayment(orderId));
    }
}
