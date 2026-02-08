package com.goeats.delivery.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 배달(Delivery) 엔티티 - 주문 건에 대한 배달 정보를 관리한다.
 *
 * <p>결제 완료 후 생성되며, 라이더 매칭 → 픽업 → 배달중 → 배달완료 상태를 추적한다.</p>
 *
 * <h3>핵심 필드 설명</h3>
 * <ul>
 *   <li>{@code orderId} - 주문 ID (unique 제약조건으로 1주문 = 1배달 보장)</li>
 *   <li>{@code status} - 배달 상태 (WAITING → RIDER_ASSIGNED → PICKED_UP → DELIVERING → DELIVERED)</li>
 *   <li>{@code lastFencingToken} - <b>Fencing Token</b>: 분산 락 만료 후 stale 업데이트 방지용 토큰</li>
 *   <li>{@code version} - JPA Optimistic Lock (@Version)으로 동시 수정 감지</li>
 * </ul>
 *
 * <h3>★ Fencing Token 패턴 (핵심!)</h3>
 * <p>분산 환경에서 Redisson 락이 만료된 후에도 이전 스레드가 DB를 업데이트할 수 있는 문제를 방지한다.
 * Redis의 AtomicLong으로 단조 증가(monotonically increasing) 토큰을 발급하고,
 * DB UPDATE 시 "현재 토큰 > 저장된 토큰"인 경우에만 업데이트가 성공한다.</p>
 *
 * <pre>
 * [시나리오: 락 만료 후 stale 업데이트 방지]
 * Thread A: 락 획득 → fencingToken=5 발급 → GC Pause로 락 만료
 * Thread B: 락 획득 → fencingToken=6 발급 → DB 업데이트 (lastFencingToken=6)
 * Thread A: GC 복귀 → fencingToken=5로 DB 업데이트 시도 → 5 < 6 이므로 거부됨!
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 DB 비관적 락(Pessimistic Lock)으로 동시성을 제어하므로
 * Fencing Token이 필요 없다. 단일 DB 트랜잭션으로 원자성이 보장되기 때문이다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 Redisson 분산 락만 사용하지만, 락 만료 후 stale 업데이트 위험이 있다.
 * Traffic 버전에서는 Fencing Token을 추가하여 락이 만료되더라도 데이터 정합성을 보장한다.</p>
 *
 * <h3>성능 최적화</h3>
 * <ul>
 *   <li>SEQUENCE 전략 + allocationSize=50: ID 채번 시 DB 왕복 횟수를 1/50로 감소</li>
 *   <li>status 컬럼 인덱스: 배달 상태별 조회 성능 최적화</li>
 * </ul>
 */
@Entity
@Table(name = "deliveries", indexes = {
        @Index(name = "idx_delivery_status", columnList = "status")  // 상태별 조회 성능 최적화
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 프록시 생성용 기본 생성자 (외부 사용 차단)
@EntityListeners(AuditingEntityListener.class)  // @CreatedDate 자동 설정을 위한 리스너
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_seq")
    @SequenceGenerator(name = "delivery_seq", sequenceName = "delivery_seq", allocationSize = 50)
    private Long id;

    @Version  // JPA Optimistic Lock - 동시 수정 시 OptimisticLockException 발생
    private Long version;

    @Column(nullable = false, unique = true)  // 1주문 = 1배달 보장
    private Long orderId;

    @Enumerated(EnumType.STRING)  // DB에 문자열로 저장 (WAITING, RIDER_ASSIGNED 등)
    @Column(nullable = false)
    private DeliveryStatus status;

    private String riderName;   // 매칭된 라이더 이름
    private String riderPhone;  // 매칭된 라이더 전화번호

    @Column(nullable = false)
    private String deliveryAddress;  // 배달 주소

    private LocalDateTime estimatedDeliveryTime;  // 예상 배달 시간

    // ★ Traffic MSA: Fencing Token for stale lock prevention
    // 분산 락 만료 후에도 stale 업데이트를 방지하는 단조 증가 토큰
    // DeliveryRepository.updateWithFencingToken()에서 "lastFencingToken < 새 토큰"일 때만 UPDATE 허용
    private Long lastFencingToken;

    @CreatedDate  // JPA Auditing으로 생성 시각 자동 설정
    private LocalDateTime createdAt;

    @Builder
    public Delivery(Long orderId, String deliveryAddress) {
        this.orderId = orderId;
        this.deliveryAddress = deliveryAddress;
        this.status = DeliveryStatus.WAITING;  // 초기 상태: 대기중
    }

    /** 라이더 매칭 완료 시 호출 - 라이더 정보 설정 및 상태 변경 */
    public void assignRider(String riderName, String riderPhone) {
        this.riderName = riderName;
        this.riderPhone = riderPhone;
        this.status = DeliveryStatus.RIDER_ASSIGNED;
        this.estimatedDeliveryTime = LocalDateTime.now().plusMinutes(30);  // 기본 30분 예상
    }

    /** 배달 상태 업데이트 (라이더 앱에서 호출) */
    public void updateStatus(DeliveryStatus newStatus) {
        this.status = newStatus;
    }

    /** Fencing Token 업데이트 - 분산 락 획득 시 새 토큰 값 저장 */
    public void updateFencingToken(Long fencingToken) {
        this.lastFencingToken = fencingToken;
    }

    /** 배달 완료 처리 */
    public void complete() {
        this.status = DeliveryStatus.DELIVERED;
    }
}
