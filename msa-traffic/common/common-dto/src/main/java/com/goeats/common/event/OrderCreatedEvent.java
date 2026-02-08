package com.goeats.common.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * ★ Traffic MSA: eventId added for idempotent event processing.
 * Each consumer tracks processed eventIds to prevent duplicate handling.
 */
public record OrderCreatedEvent(
        String eventId,
        Long orderId,
        Long userId,
        Long storeId,
        List<OrderItemDto> items,
        BigDecimal totalAmount,
        String deliveryAddress,
        String paymentMethod
) {
    public OrderCreatedEvent(Long orderId, Long userId, Long storeId,
                             List<OrderItemDto> items, BigDecimal totalAmount,
                             String deliveryAddress, String paymentMethod) {
        this(UUID.randomUUID().toString(), orderId, userId, storeId,
                items, totalAmount, deliveryAddress, paymentMethod);
    }

    public record OrderItemDto(Long menuId, int quantity, BigDecimal price) {}
}
