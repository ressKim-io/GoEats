package com.goeats.common.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * Kafka 이벤트 - 주문 생성 시 결제 서비스에 전파하는 이벤트.
 *
 * <p>주문이 생성되면 이 이벤트가 Kafka 토픽("order-created")에 발행되고,
 * 결제 서비스가 이를 소비하여 결제 처리를 시작한다.
 * 이것이 MSA에서의 Saga 패턴 첫 번째 단계이다.</p>
 *
 * <p>Java record를 이벤트로 사용하는 이유:
 * - 불변(immutable): 이벤트는 "이미 발생한 사실"이므로 변경되면 안 된다
 * - 직렬화 친화적: record의 canonical constructor가 Jackson 역직렬화에 적합
 * - 간결함: 보일러플레이트 없이 필드만 선언하면 된다</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 주문 생성과 결제를 하나의 {@code @Transactional}
 * 메서드에서 처리할 수 있다. 실패 시 DB 롤백으로 원자성이 보장된다.
 * MSA에서는 서비스가 분리되어 있으므로 이벤트를 통해 비동기 통신하고,
 * 실패 시 보상 트랜잭션(Saga)으로 데이터 정합성을 맞춘다.</p>
 */
public record OrderCreatedEvent(
    Long orderId,           // 주문 ID - 이벤트를 주문과 연결하는 키
    Long userId,            // 사용자 ID - 결제 시 사용자 확인에 필요
    Long storeId,           // 가게 ID - 가게별 결제 정산에 필요
    List<OrderItemDto> items, // 주문 항목 목록 - 결제 금액 검증에 사용
    BigDecimal totalAmount, // 총 결제 금액
    String deliveryAddress, // 배달 주소 - 배달 서비스에서 사용
    String paymentMethod    // 결제 수단 (CARD, CASH 등)
) {
    // 내부 record - 주문 항목 DTO (이벤트에 포함되는 중첩 데이터)
    public record OrderItemDto(Long menuId, int quantity, BigDecimal price) {}
}
