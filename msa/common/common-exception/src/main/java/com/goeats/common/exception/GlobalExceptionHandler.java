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
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        ErrorCode errorCode = e.getErrorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                errorCode.getStatus(), e.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/" + errorCode.name().toLowerCase()));
        problem.setTitle(errorCode.getMessage());
        return ResponseEntity.status(errorCode.getStatus()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleException(Exception e) {
        log.error("Unexpected error", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error");
        return ResponseEntity.internalServerError().body(problem);
    }
}
