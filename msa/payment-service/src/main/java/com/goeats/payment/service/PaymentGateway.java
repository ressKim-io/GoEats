package com.goeats.payment.service;

import java.math.BigDecimal;

/**
 * PG사(Payment Gateway) 연동 인터페이스.
 *
 * <p>실제 PG사(토스페이먼츠, NHN KCP 등)와의 결제 통신을 추상화합니다.
 * 인터페이스로 정의하여 구현체를 쉽게 교체할 수 있습니다.
 * (예: TossPaymentGateway, KcpPaymentGateway 등)</p>
 *
 * <p>주요 기능:
 * <ul>
 *   <li>charge: 결제 요청 (PG사에 승인 요청)</li>
 *   <li>refund: 환불 요청 (PG사에 취소/환불 요청)</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: PG 연동 코드가 같은 애플리케이션 안에 있어 @Transactional로 롤백 가능
 * - MSA: PG 호출은 외부 네트워크 통신이므로 이미 승인된 결제는 DB 롤백으로 취소 불가
 *   → 반드시 refund() 메서드로 보상 트랜잭션(Saga 보상)을 실행해야 함</p>
 */

/**
 * ★ MSA: Payment Gateway interface for external PG integration.
 * This abstraction allows swapping PG providers without changing service logic.
 */
public interface PaymentGateway {

    /** PG사에 결제 승인을 요청합니다 */
    PaymentResult charge(String orderId, BigDecimal amount, String paymentMethod);

    /** PG사에 결제 취소/환불을 요청합니다 (Saga 보상 트랜잭션에서 사용) */
    PaymentResult refund(String paymentId, BigDecimal amount);

    /**
     * PG사 응답 결과를 담는 record.
     *
     * @param success PG사 처리 성공 여부
     * @param transactionId PG사에서 발급한 거래 고유 ID (영수증 번호)
     * @param message PG사 응답 메시지 (실패 시 사유 포함)
     */
    record PaymentResult(boolean success, String transactionId, String message) {}
}
