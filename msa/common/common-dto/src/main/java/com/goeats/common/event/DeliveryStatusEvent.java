package com.goeats.common.event;

public record DeliveryStatusEvent(
    Long deliveryId,
    Long orderId,
    String status,
    String riderName,
    String riderPhone
) {}
