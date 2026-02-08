package com.goeats.delivery.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.entity.DeliveryStatus;
import com.goeats.delivery.service.DeliveryService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    @GetMapping("/{id}")
    @RateLimiter(name = "deliveryApi")
    public ApiResponse<Delivery> getDelivery(@PathVariable Long id) {
        return ApiResponse.ok(deliveryService.getDelivery(id));
    }

    @GetMapping("/order/{orderId}")
    @RateLimiter(name = "deliveryApi")
    public ApiResponse<Delivery> getDeliveryByOrderId(@PathVariable Long orderId) {
        return ApiResponse.ok(deliveryService.getDeliveryByOrderId(orderId));
    }

    @PostMapping("/{id}/status")
    @RateLimiter(name = "deliveryApi")
    public ApiResponse<Delivery> updateStatus(@PathVariable Long id,
                                              @RequestParam DeliveryStatus status) {
        return ApiResponse.ok(deliveryService.updateDeliveryStatus(id, status));
    }
}
