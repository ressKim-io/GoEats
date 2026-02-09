package com.goeats.order.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.order.dto.CreateOrderRequest;
import com.goeats.order.entity.Order;
import com.goeats.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 주문 API 컨트롤러.
 *
 * <p>MSA에서 주문 생성은 비동기로 처리됩니다:
 * POST /api/orders 호출 시 주문만 생성하고 Kafka 이벤트를 발행합니다.
 * 결제와 배달은 각각의 서비스에서 이벤트를 수신하여 별도로 처리합니다.</p>
 *
 * <p>따라서 클라이언트는 주문 생성 후 GET /api/orders/{id}로 상태를 폴링(polling)하여
 * 주문 진행 상황을 확인해야 합니다. (PAYMENT_PENDING → PAID → PREPARING ...)</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 POST /api/orders 한 번의 호출로
 * 주문 생성 + 결제 처리 + 배달 요청이 모두 동기적으로 완료됩니다.
 * 응답을 받은 시점에 이미 모든 처리가 끝난 상태입니다.</p>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 주문 생성 API.
     *
     * <p>MSA에서 이 API는 주문을 생성하고 Kafka 이벤트만 발행합니다.
     * 결제와 배달은 비동기적으로 처리되므로, 응답 시점에는 아직
     * 결제가 완료되지 않은 상태(PAYMENT_PENDING)입니다.</p>
     *
     * ★ MSA: This API only creates the order and publishes a Kafka event.
     * Payment and delivery happen asynchronously.
     * Client should poll GET /api/orders/{id} for status updates.
     *
     * Compare with Monolithic: Single API call completes entire flow
     * (order + payment + delivery) synchronously.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Order> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderService.createOrder(
                request.userId(), request.storeId(), request.menuIds(),
                request.paymentMethod(), request.deliveryAddress()));
    }

    // 주문 단건 조회 - 클라이언트가 주문 상태를 폴링할 때 사용
    @GetMapping("/{id}")
    public ApiResponse<Order> getOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.getOrder(id));
    }

    // 사용자별 주문 목록 조회
    @GetMapping("/user/{userId}")
    public ApiResponse<List<Order>> getUserOrders(@PathVariable Long userId) {
        return ApiResponse.ok(orderService.getOrdersByUser(userId));
    }

    // 주문 취소 - Saga 보상 트랜잭션을 위해 Kafka 이벤트도 함께 발행됨
    @PostMapping("/{id}/cancel")
    public ApiResponse<Order> cancelOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.cancelOrder(id));
    }
}
