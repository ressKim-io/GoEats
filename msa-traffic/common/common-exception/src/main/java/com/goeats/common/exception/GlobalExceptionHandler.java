package com.goeats.common.exception;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                errorCode.getStatus(), e.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/" +
                errorCode.name().toLowerCase()));
        return ResponseEntity.status(errorCode.getStatus()).body(problem);
    }

    // ★ Traffic MSA: Resilience4j Rate Limiter exceeded
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RequestNotPermitted e) {
        log.warn("Rate limit exceeded: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMIT_EXCEEDED.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/rate_limit_exceeded"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem);
    }

    // ★ Traffic MSA: Resilience4j Bulkhead full (thread pool exhausted)
    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ProblemDetail> handleBulkheadFull(BulkheadFullException e) {
        log.warn("Bulkhead full: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.BULKHEAD_FULL.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/bulkhead_full"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    // ★ Traffic MSA: Resilience4j Circuit Breaker open
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ProblemDetail> handleCircuitBreakerOpen(CallNotPermittedException e) {
        log.warn("Circuit breaker open: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CIRCUIT_BREAKER_OPEN.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/circuit_breaker_open"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    // ★ Traffic MSA: Timeout exceeded
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ProblemDetail> handleTimeout(TimeoutException e) {
        log.warn("Request timeout: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.GATEWAY_TIMEOUT, ErrorCode.REQUEST_TIMEOUT.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/request_timeout"));
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(problem);
    }
}
