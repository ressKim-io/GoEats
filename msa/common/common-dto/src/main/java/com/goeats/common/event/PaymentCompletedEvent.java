package com.goeats.common.event;

import java.math.BigDecimal;

public record PaymentCompletedEvent(
    Long paymentId,
    Long orderId,
    BigDecimal amount,
    String paymentMethod
) {}
