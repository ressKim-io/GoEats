package com.goeats.delivery.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 배달 조회 및 상태 업데이트 REST API 컨트롤러.
 *
 * <p>배달 "생성"은 REST API가 아닌 Kafka 이벤트(PaymentCompletedEvent)로 트리거됩니다.
 * 이 컨트롤러는 배달 조회와 상태 변경(픽업, 배달 시작, 완료, 취소) API만 제공합니다.</p>
 *
 * <p>API 목록:
 * <ul>
 *   <li>GET /api/deliveries/order/{orderId} : 주문 ID로 배달 정보 조회</li>
 *   <li>POST /api/deliveries/{id}/status?action=pickup : 배달 상태 업데이트</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: DeliveryController에서 배달 생성/조회/상태변경 모두 처리
 * - MSA: 배달 생성은 이벤트 기반, REST API는 조회와 상태 변경만 담당
 *   → 라이더 앱에서 상태 변경 API를 호출하는 구조</p>
 */
@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    /**
     * 주문 ID로 배달 정보를 조회합니다.
     * 다른 서비스(예: order-service)가 OpenFeign으로 호출할 수 있습니다.
     */
    @GetMapping("/order/{orderId}")
    public ApiResponse<Delivery> getDeliveryByOrder(@PathVariable Long orderId) {
        return ApiResponse.ok(deliveryService.getDelivery(orderId));
    }

    /**
     * 배달 상태를 업데이트합니다.
     * action 값에 따라 상태가 전이됩니다:
     * - "pickup" : RIDER_ASSIGNED → PICKED_UP (음식 수령)
     * - "deliver" : PICKED_UP → DELIVERING (배달 출발)
     * - "complete" : DELIVERING → DELIVERED (배달 완료)
     * - "cancel" : 현재 상태 → CANCELLED (배달 취소)
     */
    @PostMapping("/{id}/status")
    public ApiResponse<Delivery> updateStatus(@PathVariable Long id,
                                              @RequestParam String action) {
        return ApiResponse.ok(deliveryService.updateStatus(id, action));
    }
}
