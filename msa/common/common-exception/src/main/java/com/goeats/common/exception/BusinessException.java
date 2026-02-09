package com.goeats.common.exception;

import lombok.Getter;

/**
 * 비즈니스 예외 - {@link ErrorCode} 기반으로 표준화된 예외를 던지는 커스텀 예외 클래스.
 *
 * <p>모든 비즈니스 로직 예외는 이 클래스를 사용한다.
 * ErrorCode를 통해 HTTP 상태 코드와 에러 메시지가 자동으로 결정되므로,
 * 개발자가 예외마다 상태 코드를 직접 지정할 필요가 없다.</p>
 *
 * <p>두 가지 생성자를 제공하는 이유:
 * - {@code BusinessException(ErrorCode)}: 표준 에러 메시지 사용
 * - {@code BusinessException(ErrorCode, String)}: 상세 메시지 커스터마이징
 *   (예: "Order not found" 대신 "Order #1234 not found")</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서도 동일한 패턴을 사용할 수 있다.
 * 하지만 MSA에서는 이 예외 클래스가 공통 모듈(common-exception)에 있어
 * 모든 마이크로서비스가 동일한 예외 체계를 공유한다.
 * 서비스마다 다른 예외 형식을 사용하면 클라이언트 파싱이 복잡해지기 때문이다.</p>
 */
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
