package com.goeats.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 주문 생성 요청 DTO.
 *
 * <p>Bean Validation으로 요청 데이터를 검증하여 잘못된 요청이
 * 서비스 레이어까지 전달되는 것을 방지한다.</p>
 */
public record CreateOrderRequest(
        @NotNull(message = "userId is required")
        Long userId,

        @NotNull(message = "storeId is required")
        Long storeId,

        @NotEmpty(message = "menuIds must not be empty")
        List<Long> menuIds,

        @NotBlank(message = "paymentMethod is required")
        String paymentMethod,

        @NotBlank(message = "deliveryAddress is required")
        String deliveryAddress
) {}
