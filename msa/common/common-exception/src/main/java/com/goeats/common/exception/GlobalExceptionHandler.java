package com.goeats.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * ★ MSA: Each microservice includes this shared exception handler.
 * Uses RFC 9457 ProblemDetail format for standardized error responses.
 *
 * Compare with Monolithic: Same handler but in a single application.
 */
/**
 * RFC 9457 ProblemDetail 형식의 글로벌 예외 처리기.
 *
 * <p>Spring 6의 {@link ProblemDetail}을 사용하여 표준화된 에러 응답을 반환한다.
 * ProblemDetail은 RFC 9457(구 RFC 7807)에 정의된 형식으로,
 * type, title, status, detail 등의 필드를 가진다.</p>
 *
 * <p>응답 예시:
 * <pre>{@code
 * {
 *   "type": "https://goeats.com/errors/order_not_found",
 *   "title": "Order not found",
 *   "status": 404,
 *   "detail": "Order #1234 not found"
 * }
 * }</pre></p>
 *
 * <p>{@code @RestControllerAdvice}로 선언하여 모든 컨트롤러의 예외를 한 곳에서 처리한다.
 * 공통 모듈에 위치하므로 각 마이크로서비스가 별도로 예외 핸들러를 구현할 필요가 없다.</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서도 동일한 {@code @RestControllerAdvice}를 사용한다.
 * 차이점은 MSA에서는 이 핸들러가 공통 모듈에 있어 서비스 간 에러 응답 형식이 통일된다는 것이다.
 * 만약 서비스마다 다른 에러 형식을 사용하면, API Gateway나 프론트엔드에서 처리가 복잡해진다.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * BusinessException 처리 - ErrorCode에 정의된 HTTP 상태와 메시지로 응답 생성.
     * type URI를 설정하여 클라이언트가 에러 유형을 프로그래밍적으로 구분할 수 있게 한다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        ErrorCode errorCode = e.getErrorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                errorCode.getStatus(), e.getMessage());
        // type URI: 클라이언트가 에러 종류를 코드로 구분할 수 있는 식별자
        problem.setType(URI.create("https://goeats.com/errors/" + errorCode.name().toLowerCase()));
        problem.setTitle(errorCode.getMessage());
        return ResponseEntity.status(errorCode.getStatus()).body(problem);
    }

    /**
     * 예상치 못한 예외 처리 - 500 Internal Server Error로 응답.
     * 보안을 위해 실제 예외 메시지는 클라이언트에 노출하지 않고, 로그에만 기록한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleException(Exception e) {
        log.error("Unexpected error", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error");
        return ResponseEntity.internalServerError().body(problem);
    }
}
