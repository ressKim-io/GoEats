package com.goeats.order.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.order.entity.Order;
import com.goeats.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * ★ MSA: This API only creates the order and publishes a Kafka event.
     * Payment and delivery happen asynchronously.
     * Client should poll GET /api/orders/{id} for status updates.
     *
     * Compare with Monolithic: Single API call completes entire flow
     * (order + payment + delivery) synchronously.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Order> createOrder(@RequestParam Long userId,
                                          @RequestParam Long storeId,
                                          @RequestParam List<Long> menuIds,
                                          @RequestParam String paymentMethod,
                                          @RequestParam String deliveryAddress) {
        return ApiResponse.ok(orderService.createOrder(
                userId, storeId, menuIds, paymentMethod, deliveryAddress));
    }

    @GetMapping("/{id}")
    public ApiResponse<Order> getOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.getOrder(id));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<Order>> getUserOrders(@PathVariable Long userId) {
        return ApiResponse.ok(orderService.getOrdersByUser(userId));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Order> cancelOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.cancelOrder(id));
    }
}
