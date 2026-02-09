package com.goeats.common.event;

/**
 * Kafka 이벤트 - 배달 상태 변경 시 다른 서비스(주문, 알림 등)에 전파하는 이벤트.
 *
 * <p>배달 상태가 변경될 때마다(라이더 배정, 픽업, 배달 완료 등) 이 이벤트가 Kafka 토픽에 발행된다.
 * 주문 서비스는 이 이벤트를 소비(consume)하여 주문 상태를 동기화한다.</p>
 *
 * <p>riderName, riderPhone을 포함하는 이유: 소비자 서비스가 배달 서비스에 다시 조회하지 않도록
 * 필요한 정보를 이벤트에 담아 전송한다 (이벤트 자급자족 원칙, Event Carried State Transfer).</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 {@code ApplicationEventPublisher}를 사용하여
 * 같은 JVM 내에서 동기적으로 이벤트를 전달한다. 트랜잭션도 공유할 수 있어 데이터 일관성이 쉽다.
 * MSA에서는 Kafka를 통해 네트워크를 넘어 비동기로 전달하므로,
 * 최종 일관성(Eventual Consistency)을 받아들여야 한다.</p>
 */
public record DeliveryStatusEvent(
    Long deliveryId,
    Long orderId,
    String status,       // 배달 상태: ASSIGNED, PICKED_UP, DELIVERED 등
    String riderName,    // 라이더 이름 - 이벤트 자급자족을 위해 포함
    String riderPhone    // 라이더 연락처 - 이벤트 자급자족을 위해 포함
) {}
