package com.goeats.delivery.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.entity.DeliveryStatus;
import com.goeats.delivery.service.DeliveryService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 배달 API 컨트롤러 - 배달 조회 및 상태 변경 엔드포인트 제공.
 *
 * <p>배달 정보 조회(ID/주문ID 기반)와 배달 상태 업데이트 API를 제공한다.
 * 배달 생성은 Kafka 이벤트(PaymentCompletedEvent) 수신으로 자동 처리되므로
 * 컨트롤러에 생성 API는 없다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 주문/결제/배달이 하나의 트랜잭션 안에서 동기적으로 처리된다.
 * MSA에서는 배달 서비스가 독립 API를 노출하며, Gateway를 통해 라우팅된다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에는 Rate Limiting이 없어 트래픽 폭주 시 서비스가 과부하될 수 있다.
 * Traffic 버전에서는 {@code @RateLimiter}를 컨트롤러 레벨에 적용하여
 * Gateway Rate Limiting과 함께 <b>이중 방어(Defense-in-Depth)</b>를 구현한다.</p>
 *
 * <h3>API 목록</h3>
 * <ul>
 *   <li>GET /api/deliveries/{id} - 배달 ID로 조회</li>
 *   <li>GET /api/deliveries/order/{orderId} - 주문 ID로 배달 조회</li>
 *   <li>POST /api/deliveries/{id}/status - 배달 상태 변경 (라이더 앱에서 호출)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    /** 배달 ID로 배달 정보 조회 */
    @GetMapping("/{id}")
    @RateLimiter(name = "deliveryApi")  // 서비스 레벨 Rate Limiting (Gateway와 이중 방어)
    public ApiResponse<Delivery> getDelivery(@PathVariable Long id) {
        return ApiResponse.ok(deliveryService.getDelivery(id));
    }

    /** 주문 ID로 배달 정보 조회 (주문 서비스에서 배달 상태 확인 시 사용) */
    @GetMapping("/order/{orderId}")
    @RateLimiter(name = "deliveryApi")
    public ApiResponse<Delivery> getDeliveryByOrderId(@PathVariable Long orderId) {
        return ApiResponse.ok(deliveryService.getDeliveryByOrderId(orderId));
    }

    /** 배달 상태 변경 - 라이더 앱에서 픽업/배달중/배달완료 등 상태를 업데이트 */
    @PostMapping("/{id}/status")
    @RateLimiter(name = "deliveryApi")
    public ApiResponse<Delivery> updateStatus(@PathVariable Long id,
                                              @RequestParam DeliveryStatus status) {
        return ApiResponse.ok(deliveryService.updateDeliveryStatus(id, status));
    }
}
