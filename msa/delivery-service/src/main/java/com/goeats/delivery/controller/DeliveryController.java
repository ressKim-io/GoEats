package com.goeats.delivery.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    @GetMapping("/order/{orderId}")
    public ApiResponse<Delivery> getDeliveryByOrder(@PathVariable Long orderId) {
        return ApiResponse.ok(deliveryService.getDelivery(orderId));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Delivery> updateStatus(@PathVariable Long id,
                                              @RequestParam String action) {
        return ApiResponse.ok(deliveryService.updateStatus(id, action));
    }
}
