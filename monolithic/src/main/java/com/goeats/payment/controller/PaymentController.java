package com.goeats.payment.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentRepository paymentRepository;

    @GetMapping("/order/{orderId}")
    public ApiResponse<Payment> getPaymentByOrder(@PathVariable Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("Payment not found"));
    }
}
