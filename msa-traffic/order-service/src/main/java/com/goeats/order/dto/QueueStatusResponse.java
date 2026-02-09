package com.goeats.order.dto;

/**
 * 주문 대기열 상태 응답 DTO
 *
 * <p>피크타임에 주문이 대기열에 들어갔을 때, 클라이언트에게 현재 대기 상태를 알려주는 응답.</p>
 *
 * <h3>시나리오</h3>
 * <pre>
 * 피크타임(점심/저녁) → 주문 폭주 → 시스템 과부하 방지
 * → 주문을 Redis Sorted Set 대기열에 넣고 순서대로 처리
 * → 클라이언트에게 현재 대기 순번과 예상 대기 시간 반환
 * </pre>
 *
 * <h3>★ 대기열 패턴 (티켓팅과 동일)</h3>
 * <p>Redis Sorted Set의 score를 타임스탬프로 사용하여 FIFO 순서를 보장.
 * ZRANK로 현재 순번을 O(log N)으로 조회.</p>
 *
 * @param orderId              대기열에 들어간 주문 ID
 * @param position             현재 대기 순번 (0-based, 0이면 다음 처리 대상)
 * @param estimatedWaitSeconds 예상 대기 시간 (초 단위, position * 처리 간격)
 * @param queueSize            전체 대기열 크기
 *
 * ★ Queue status response for peak-time order throttling
 *   Uses Redis Sorted Set (same pattern as ticketing queues)
 */
public record QueueStatusResponse(
        Long orderId,
        long position,
        long estimatedWaitSeconds,
        long queueSize
) {
}
