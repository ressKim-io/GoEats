package com.goeats.common.exception;

import lombok.Getter;

/**
 * 비즈니스 예외 (Business Exception)
 *
 * <p>도메인 규칙 위반 시 발생하는 체크되지 않는(unchecked) 예외.
 * ErrorCode enum과 결합하여 HTTP 상태 코드와 에러 메시지를 함께 전달.</p>
 *
 * <h3>사용 예시</h3>
 * <pre>
 *   // 주문을 찾을 수 없는 경우
 *   throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
 *
 *   // 커스텀 메시지가 필요한 경우
 *   throw new BusinessException(ErrorCode.PAYMENT_FAILED, "PG사 응답 타임아웃");
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서도 동일한 패턴 사용 가능. 차이점은 MSA에서는 이 예외가
 * 각 마이크로서비스에서 독립적으로 처리되며, GlobalExceptionHandler를 통해
 * RFC 7807 ProblemDetail 형식으로 변환됨.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic과 구조는 동일하지만, MSA-Traffic에서는 ErrorCode에
 * Resilience4j 관련 에러 코드(RATE_LIMIT_EXCEEDED, CIRCUIT_BREAKER_OPEN 등)가
 * 추가되어 트래픽 제어 상황도 비즈니스 예외로 통합 처리.</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 에러 코드 (HTTP 상태 코드 + 메시지를 포함하는 enum) */
    private final ErrorCode errorCode;

    /**
     * ErrorCode의 기본 메시지를 사용하는 생성자.
     * @param errorCode 에러 코드 enum 값
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 커스텀 메시지를 지정하는 생성자.
     * ErrorCode의 기본 메시지 대신 상세 메시지를 전달할 때 사용.
     * @param errorCode 에러 코드 enum 값
     * @param message 커스텀 에러 메시지
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
