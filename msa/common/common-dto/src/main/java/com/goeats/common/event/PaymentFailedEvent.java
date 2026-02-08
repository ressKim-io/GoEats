package com.goeats.common.event;

public record PaymentFailedEvent(
    Long orderId,
    String reason
) {}
