package com.goeats.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 API 응답 래퍼 - 모든 MSA 서비스가 동일한 응답 포맷을 사용하는 표준 응답 객체.
 *
 * <p>success, data, message 세 필드로 구성되어 클라이언트가 응답을 일관되게 파싱할 수 있다.
 * {@code @JsonInclude(NON_NULL)}을 사용하여 null인 필드는 JSON에서 제외한다.
 * (예: 성공 시 message는 null이므로 응답에 포함되지 않음)</p>
 *
 * <p>Java 17의 record를 사용하여 불변(immutable) 객체로 만들었다.
 * record는 equals/hashCode/toString을 자동 생성하므로 DTO에 적합하다.</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서도 동일한 응답 래퍼를 사용할 수 있지만,
 * 하나의 프로젝트 내에 존재한다. MSA에서는 이를 별도 모듈(common-dto)로 분리하여
 * 여러 마이크로서비스가 Gradle 의존성으로 공유한다.
 * 이렇게 하면 서비스 간 API 응답 포맷이 항상 동일하게 유지된다.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, String message) {
    // 성공 응답 팩토리 메서드 - data만 포함, message는 null (JSON에서 제외됨)
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }
    // 에러 응답 팩토리 메서드 - data는 null, message에 에러 내용 포함
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
