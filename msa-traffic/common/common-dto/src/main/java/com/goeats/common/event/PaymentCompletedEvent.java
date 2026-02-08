package com.goeats.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ★ Traffic MSA: eventId added for idempotent event processing.
 * Prevents duplicate payment confirmation handling in Order/Delivery services.
 */
public record PaymentCompletedEvent(
        String eventId,
        Long paymentId,
        Long orderId,
        BigDecimal amount,
        String paymentMethod
) {
    public PaymentCompletedEvent(Long paymentId, Long orderId,
                                 BigDecimal amount, String paymentMethod) {
        this(UUID.randomUUID().toString(), paymentId, orderId, amount, paymentMethod);
    }
}
