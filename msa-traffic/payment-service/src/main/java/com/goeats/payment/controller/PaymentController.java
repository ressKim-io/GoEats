package com.goeats.payment.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.service.PaymentService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/{id}")
    @RateLimiter(name = "paymentApi")
    public ApiResponse<Payment> getPayment(@PathVariable Long id) {
        return ApiResponse.ok(paymentService.getPayment(id));
    }

    @PostMapping("/{orderId}/refund")
    @RateLimiter(name = "paymentApi")
    public ApiResponse<String> refund(@PathVariable Long orderId) {
        paymentService.refund(orderId);
        return ApiResponse.ok("Refund processed for order: " + orderId);
    }
}
