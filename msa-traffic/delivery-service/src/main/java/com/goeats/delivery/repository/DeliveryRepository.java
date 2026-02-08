package com.goeats.delivery.repository;

import com.goeats.delivery.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 배달(Delivery) 레포지토리 - 배달 데이터 접근 계층.
 *
 * <p>기본 CRUD 외에 Fencing Token 기반 UPDATE 쿼리를 제공하여
 * 분산 환경에서의 stale 업데이트를 DB 레벨에서 방지한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 비관적 락(SELECT FOR UPDATE)으로 동시성을 제어한다.
 * MSA에서는 분산 락(Redisson) + Fencing Token으로 서비스 간 동시성을 관리한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 분산 락만 사용하여 락 만료 시 stale 업데이트 위험이 있다.
 * Traffic 버전에서는 updateWithFencingToken() 쿼리로 DB 레벨 최종 방어선을 구축한다.</p>
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    /** 주문 ID로 배달 조회 (1주문 = 1배달이므로 Optional) */
    Optional<Delivery> findByOrderId(Long orderId);

    /**
     * ★ Fencing Token 기반 UPDATE - 분산 락의 stale 업데이트 방지 (핵심 쿼리!).
     *
     * <p>라이더 배정 시 Fencing Token을 함께 기록하며, 제공된 토큰이 저장된 토큰보다
     * 클 때만 UPDATE가 실행된다. 이를 통해 락 만료 후 이전 스레드가 더 새로운 데이터를
     * 덮어쓰는 것을 DB 레벨에서 원천 차단한다.</p>
     *
     * <h4>WHERE 조건 설명</h4>
     * <pre>
     * WHERE d.id = :id
     *   AND (d.lastFencingToken IS NULL          -- 최초 업데이트 (토큰 없음)
     *        OR d.lastFencingToken < :fencingToken) -- 새 토큰이 더 큰 경우만 허용
     * </pre>
     *
     * <h4>반환값</h4>
     * <ul>
     *   <li>1: 업데이트 성공 (토큰이 최신)</li>
     *   <li>0: 업데이트 거부 (토큰이 stale - 더 새로운 토큰이 이미 기록됨)</li>
     * </ul>
     *
     * ★ Fencing Token UPDATE: only updates if the provided fencing token
     * is greater than the stored one. Prevents stale lock holders from
     * overwriting newer data.
     *
     * Returns 1 if updated (token is newer), 0 if skipped (token is stale).
     */
    @Modifying
    @Query("UPDATE Delivery d SET d.riderName = :riderName, d.riderPhone = :riderPhone, " +
            "d.status = com.goeats.delivery.entity.DeliveryStatus.RIDER_ASSIGNED, " +
            "d.lastFencingToken = :fencingToken " +
            "WHERE d.id = :id AND (d.lastFencingToken IS NULL OR d.lastFencingToken < :fencingToken)")
    int updateWithFencingToken(@Param("id") Long id,
                               @Param("riderName") String riderName,
                               @Param("riderPhone") String riderPhone,
                               @Param("fencingToken") Long fencingToken);
}
