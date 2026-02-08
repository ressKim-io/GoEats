package com.goeats.common.event;

import java.util.UUID;

/**
 * ★ Traffic MSA: eventId added for idempotent saga compensation.
 * Ensures order cancellation happens exactly once on payment failure.
 */
public record PaymentFailedEvent(
        String eventId,
        Long orderId,
        String reason
) {
    public PaymentFailedEvent(Long orderId, String reason) {
        this(UUID.randomUUID().toString(), orderId, reason);
    }
}
