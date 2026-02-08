package com.goeats.order.entity;

/**
 * Saga 상태 머신 열거형 - 분산 트랜잭션의 진행 상태
 *
 * <h3>상태 전이 다이어그램</h3>
 * <pre>
 * 정상 흐름 (Happy Path):
 *   STARTED → COMPLETED
 *   (주문 생성 → 결제 성공 → 배달 완료)
 *
 * 실패 흐름 (Compensation):
 *   STARTED → COMPENSATING → FAILED
 *   (주문 생성 → 결제 실패 → 주문 취소)
 * </pre>
 *
 * <h3>각 상태 설명</h3>
 * <ul>
 *   <li>STARTED: Saga 시작됨 (주문 생성 완료, 후속 단계 진행 중)</li>
 *   <li>COMPENSATING: 보상 트랜잭션 실행 중 (이전 단계 롤백 진행)</li>
 *   <li>COMPLETED: Saga 성공적으로 완료 (모든 단계 성공)</li>
 *   <li>FAILED: Saga 최종 실패 (보상 완료 후 종료)</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 Saga 상태 추적이 없어 분산 트랜잭션의 진행 상황을 알 수 없었다.
 * MSA-Traffic에서는 SagaStatus로 명확한 상태 머신을 정의하여
 * 장애 발생 시 어느 상태에서 멈췄는지 파악할 수 있다.
 *
 * ★ Saga State Machine Status
 *
 * STARTED → COMPENSATING → FAILED (payment failed, order cancelled)
 * STARTED → COMPLETED (happy path: payment + delivery succeeded)
 */
public enum SagaStatus {
    STARTED,        // Saga 시작됨 (진행 중)
    COMPENSATING,   // 보상 트랜잭션 실행 중
    COMPLETED,      // 모든 단계 성공 완료
    FAILED          // 최종 실패 (보상 완료)
}
