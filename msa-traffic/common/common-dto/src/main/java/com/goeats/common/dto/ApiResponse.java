package com.goeats.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 API 응답 래퍼 (Common API Response Wrapper)
 *
 * <p>모든 마이크로서비스가 동일한 응답 형식을 사용하도록 강제하는 공통 DTO.
 * success/data/message 3개 필드로 클라이언트가 일관된 응답 파싱 가능.</p>
 *
 * <h3>사용 예시</h3>
 * <pre>
 *   // 성공: {"success": true, "data": {...}}
 *   return ApiResponse.ok(orderDto);
 *
 *   // 실패: {"success": false, "message": "Order not found"}
 *   return ApiResponse.error("Order not found");
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 각 컨트롤러가 자유로운 응답 형식을 사용해도 내부 호출이므로 문제 없음.
 * MSA에서는 서비스 간 API 호출이 발생하므로 공통 응답 형식이 필수적.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic과 동일한 구조. MSA-Traffic에서는 Gateway를 통해 모든 응답이
 * 이 형식으로 통일되어 클라이언트에 전달됨.</p>
 *
 * <p>Java 16+ record 타입 사용 → 불변 객체, equals/hashCode/toString 자동 생성.</p>
 *
 * @param <T> 응답 데이터의 타입 (제네릭)
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // null 필드는 JSON에서 제외 (불필요한 네트워크 전송 방지)
public record ApiResponse<T>(
        boolean success, // 요청 성공 여부
        T data,          // 성공 시 응답 데이터 (실패 시 null)
        String message   // 실패 시 에러 메시지 (성공 시 null)
) {
    /** 성공 응답 팩토리 메서드 - data를 포함한 성공 응답 생성 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 실패 응답 팩토리 메서드 - 에러 메시지를 포함한 실패 응답 생성 */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
