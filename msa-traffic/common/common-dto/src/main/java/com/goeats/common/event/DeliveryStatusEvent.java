package com.goeats.common.event;

import java.util.UUID;

/**
 * ★ Traffic MSA: eventId added for idempotent delivery status updates.
 * Prevents duplicate status change notifications.
 */
public record DeliveryStatusEvent(
        String eventId,
        Long deliveryId,
        Long orderId,
        String status,
        String riderName,
        String riderPhone
) {
    public DeliveryStatusEvent(Long deliveryId, Long orderId,
                               String status, String riderName, String riderPhone) {
        this(UUID.randomUUID().toString(), deliveryId, orderId,
                status, riderName, riderPhone);
    }
}
