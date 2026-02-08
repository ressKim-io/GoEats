package com.goeats.common.event;

import java.math.BigDecimal;
import java.util.List;

public record OrderCreatedEvent(
    Long orderId,
    Long userId,
    Long storeId,
    List<OrderItemDto> items,
    BigDecimal totalAmount,
    String deliveryAddress,
    String paymentMethod
) {
    public record OrderItemDto(Long menuId, int quantity, BigDecimal price) {}
}
