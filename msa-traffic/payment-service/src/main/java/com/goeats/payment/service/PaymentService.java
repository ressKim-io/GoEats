package com.goeats.payment.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.entity.PaymentStatus;
import com.goeats.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 결제 서비스 - 결제 처리의 핵심 비즈니스 로직을 담당한다.
 *
 * <p>결제 생성, PG사 승인, 환불 등 결제 관련 비즈니스 로직을 처리한다.
 * 특히 이중 멱등성 체크로 중복 결제를 완벽하게 방지한다.</p>
 *
 * <h3>이중 멱등성 체크 (Double-Payment Prevention)</h3>
 * <ol>
 *   <li><b>orderId 체크</b> - 같은 주문에 대한 중복 결제 방지.
 *       Kafka 이벤트가 재전달되어 같은 orderId로 결제가 요청되는 경우.</li>
 *   <li><b>idempotencyKey 체크</b> - 클라이언트 재시도로 인한 중복 결제 방지.
 *       네트워크 타임아웃 후 클라이언트가 같은 멱등성 키로 재요청하는 경우.</li>
 * </ol>
 *
 * <h3>Outbox 패턴과의 연계</h3>
 * <p>이 서비스에서 결제를 처리한 후, OrderEventListener가 결과를 Outbox에 저장한다.
 * MSA Basic에서는 이 서비스에서 직접 KafkaTemplate.send()를 호출했지만,
 * Traffic에서는 "DB 저장과 이벤트 발행의 원자성"을 보장하기 위해 Outbox 패턴을 사용한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 existsByOrderId만으로 단일 체크했고, idempotencyKey가 없었다.
 * 또한 결제 후 직접 Kafka로 이벤트를 발행하여, DB 커밋 성공 + Kafka 발행 실패 시
 * 데이터 불일치가 발생할 수 있었다. Traffic에서는 이중 멱등성 + Outbox로 이 문제를 해결한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 @Transactional 하나로 주문-결제가 같은 DB 트랜잭션에서 처리되었다.
 * 실패 시 전체 롤백되므로 중복 방지 로직이 단순했다.
 * MSA에서는 서비스 간 DB가 분리되어 있으므로, 멱등성 키와 여러 단계의 중복 체크가 필수이다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // 기본적으로 읽기 전용 트랜잭션 (조회 성능 최적화)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    /**
     * 결제 처리 - 이중 멱등성 체크 후 결제를 생성한다.
     *
     * <p>처리 순서:</p>
     * <ol>
     *   <li>orderId로 기존 결제 존재 여부 확인 → 존재하면 기존 결제 반환</li>
     *   <li>idempotencyKey로 기존 결제 존재 여부 확인 → 존재하면 기존 결제 반환</li>
     *   <li>두 체크 모두 통과 시 → 신규 결제 생성</li>
     * </ol>
     *
     * @param orderId        주문 ID
     * @param amount         결제 금액
     * @param paymentMethod  결제 수단 (CARD, CASH 등)
     * @param idempotencyKey 멱등성 키 (이벤트의 eventId 또는 클라이언트 제공 키)
     * @return 생성된 또는 기존 결제 정보
     */
    @Transactional  // 쓰기 트랜잭션 (readOnly = false 오버라이드)
    public Payment processPayment(Long orderId, BigDecimal amount,
                                  String paymentMethod, String idempotencyKey) {
        // ★ Idempotent check 1: by orderId - Kafka 이벤트 중복 전달 대응
        if (paymentRepository.existsByOrderId(orderId)) {
            log.warn("Payment already exists for order: {}", orderId);
            return paymentRepository.findByOrderId(orderId).orElseThrow();
        }

        // ★ Idempotent check 2: by idempotencyKey - 클라이언트 재시도 대응
        if (idempotencyKey != null) {
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> {
                        log.warn("Duplicate payment detected by idempotencyKey: {}", idempotencyKey);
                        return existing;  // 기존 결제 반환 (중복 생성 방지)
                    })
                    .orElseGet(() -> createPayment(orderId, amount, paymentMethod, idempotencyKey));
        }

        return createPayment(orderId, amount, paymentMethod, null);
    }

    /**
     * 실제 결제 생성 및 PG사 승인 처리.
     *
     * <p>Payment 엔티티를 생성하고, PG사 API를 호출하여 승인 처리한다.
     * 현재는 PG사 호출을 시뮬레이션하며, 실제 환경에서는 외부 API 호출로 대체된다.</p>
     *
     * @param orderId        주문 ID
     * @param amount         결제 금액
     * @param paymentMethod  결제 수단
     * @param idempotencyKey 멱등성 키
     * @return 저장된 결제 정보
     */
    private Payment createPayment(Long orderId, BigDecimal amount,
                                  String paymentMethod, String idempotencyKey) {
        Payment payment = Payment.builder()
                .orderId(orderId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .idempotencyKey(idempotencyKey)
                .build();

        try {
            log.info("Processing payment: orderId={}, amount={}", orderId, amount);
            // Simulate PG call - 실제 환경에서는 PG사 API 호출 (토스, 카카오페이 등)
            payment.complete();  // 승인 성공 → COMPLETED 상태로 전이
        } catch (Exception e) {
            payment.fail();  // 승인 실패 → FAILED 상태로 전이
            log.error("Payment failed: orderId={}", orderId, e);
        }

        return paymentRepository.save(payment);  // DB에 결제 정보 저장
    }

    /**
     * 환불 처리 - 주문 ID로 결제를 찾아 환불 상태로 변경한다.
     *
     * <p>Saga 보상 트랜잭션에서 호출될 수 있다. 배달 매칭 실패 등으로 주문이 취소되면
     * 결제도 환불 처리해야 하며, 이때 이 메서드가 호출된다.</p>
     *
     * @param orderId 환불할 주문 ID
     * @throws BusinessException 결제를 찾을 수 없는 경우 (PAYMENT_NOT_FOUND)
     */
    @Transactional  // 쓰기 트랜잭션
    public void refund(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.refund();  // REFUNDED 상태로 전이 (JPA dirty checking으로 자동 UPDATE)
        log.info("Payment refunded: orderId={}", orderId);
    }

    /**
     * 결제 단건 조회.
     *
     * @param paymentId 결제 ID (PK)
     * @return 결제 정보
     * @throws BusinessException 결제를 찾을 수 없는 경우 (PAYMENT_NOT_FOUND)
     */
    public Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
