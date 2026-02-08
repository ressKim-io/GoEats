package com.goeats.order.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.order.entity.Order;
import com.goeats.order.service.OrderService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

/**
 * ★ Traffic MSA: Idempotency-Key header for duplicate request prevention
 *
 * vs Basic MSA: No idempotency → duplicate orders on network retry
 *
 * Flow:
 *   1. Client sends POST with Idempotency-Key header
 *   2. Check Redis if key already used
 *   3. If used → return DUPLICATE_REQUEST error (409)
 *   4. If new → process + store key in Redis with TTL
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final RedisTemplate<String, Object> redisTemplate;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimiter(name = "orderApi")
    public ApiResponse<Order> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam Long userId,
            @RequestParam Long storeId,
            @RequestParam List<Long> menuIds,
            @RequestParam String paymentMethod,
            @RequestParam String deliveryAddress) {

        // ★ Idempotency check via Redis
        if (idempotencyKey != null) {
            String redisKey = "idempotency:order:" + idempotencyKey;
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
            if (Boolean.FALSE.equals(isNew)) {
                throw new BusinessException(ErrorCode.DUPLICATE_REQUEST,
                        "Duplicate order request detected for key: " + idempotencyKey);
            }
        }

        return ApiResponse.ok(orderService.createOrder(
                userId, storeId, menuIds, paymentMethod, deliveryAddress));
    }

    @GetMapping("/{id}")
    public ApiResponse<Order> getOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.getOrder(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Order> cancelOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.cancelOrder(id));
    }
}
