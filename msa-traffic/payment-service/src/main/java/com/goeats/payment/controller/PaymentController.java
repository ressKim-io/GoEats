package com.goeats.payment.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.service.PaymentService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 결제 API 컨트롤러.
 *
 * <p>결제 조회, 환불 등 결제 관련 REST API를 제공한다.
 * 실제 결제 생성은 Kafka 이벤트 기반으로 {@code OrderEventListener}에서 처리되므로,
 * 이 컨트롤러에는 결제 생성 API가 없다 (이벤트 드리븐 아키텍처).</p>
 *
 * <h3>적용된 트래픽 패턴</h3>
 * <ul>
 *   <li><b>@RateLimiter("paymentApi")</b> - Resilience4j 기반 API 호출 빈도 제한.
 *       결제 API에 대한 과도한 요청을 방지하여 PG사 연동 부하를 보호한다.
 *       설정값은 application.yml의 resilience4j.ratelimiter.instances.paymentApi에서 관리.</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 Rate Limiting 없이 모든 요청을 그대로 처리했다.
 * Traffic에서는 @RateLimiter를 적용하여 초당 요청 수를 제한하고,
 * 한도 초과 시 RequestNotPermitted 예외를 발생시켜 서비스를 보호한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 결제가 동기 메서드 호출로 처리되었다 (OrderService -> PaymentService).
 * MSA에서는 결제 생성이 Kafka 이벤트로 비동기 처리되고, REST API는 조회/환불 용도로만 사용된다.
 * 또한 Gateway 레벨의 Rate Limiting과 서비스 레벨의 Rate Limiting이 이중으로 적용된다.</p>
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 결제 단건 조회.
     * <p>@RateLimiter가 적용되어 초당 허용된 요청 수를 초과하면 429 에러를 반환한다.</p>
     *
     * @param id 결제 ID (PK)
     * @return 결제 정보
     */
    @GetMapping("/{id}")
    @RateLimiter(name = "paymentApi")
    public ApiResponse<Payment> getPayment(@PathVariable Long id) {
        return ApiResponse.ok(paymentService.getPayment(id));
    }

    /**
     * 주문 ID 기반 환불 처리.
     * <p>결제 상태를 REFUNDED로 변경한다. Saga 보상 트랜잭션에서 호출될 수 있다.</p>
     *
     * @param orderId 주문 ID
     * @return 환불 처리 결과 메시지
     */
    @PostMapping("/{orderId}/refund")
    @RateLimiter(name = "paymentApi")
    public ApiResponse<String> refund(@PathVariable Long orderId) {
        paymentService.refund(orderId);
        return ApiResponse.ok("Refund processed for order: " + orderId);
    }
}
