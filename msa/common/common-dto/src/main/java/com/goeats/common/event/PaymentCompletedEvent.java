package com.goeats.common.event;

import java.math.BigDecimal;

/**
 * 결제 완료 이벤트 - 결제가 성공하면 배달 서비스와 주문 서비스에 전파하는 이벤트.
 *
 * <p>Saga 패턴의 두 번째 단계: 결제 서비스가 PG사 결제를 완료한 후 이 이벤트를 발행한다.
 * - 주문 서비스: 주문 상태를 PAID로 변경
 * - 배달 서비스: 라이더 매칭을 시작</p>
 *
 * <p>이벤트에 paymentId와 amount를 포함하는 이유: 소비자 서비스가 결제 서비스에
 * 다시 조회하지 않아도 되도록 하기 위함이다. 서비스 간 동기 호출을 최소화하면
 * 결합도가 낮아지고 장애 전파 위험이 줄어든다.</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 결제 완료 후 바로 같은 트랜잭션 내에서
 * 배달 생성 메서드를 호출한다 ({@code deliveryService.createDelivery(order)}).
 * MSA에서는 이 이벤트를 통해 느슨한 결합(Loose Coupling)으로 통신하며,
 * 결제 서비스는 배달 서비스의 존재를 알 필요가 없다.</p>
 */
public record PaymentCompletedEvent(
    Long paymentId,       // 결제 ID - 결제 추적 및 환불 시 사용
    Long orderId,         // 주문 ID - 이벤트를 주문과 연결하는 상관 관계 키(Correlation ID)
    BigDecimal amount,    // 결제 금액 - 소비자 서비스에서 검증 용도
    String paymentMethod  // 결제 수단 - 배달 서비스에서 현금 결제 여부 확인에 사용
) {}
