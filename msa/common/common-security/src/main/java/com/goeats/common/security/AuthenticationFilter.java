package com.goeats.common.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * JWT 인증 필터 - 모든 MSA 서비스에서 공유하는 인증 처리 필터.
 *
 * <p>HTTP 요청의 Authorization 헤더에서 JWT 토큰을 추출하고,
 * 토큰이 유효하면 userId를 요청 속성(attribute)에 저장한다.
 * 이후 컨트롤러에서 {@code request.getAttribute("userId")}로 인증된 사용자 정보를 사용한다.</p>
 *
 * <p>Jakarta Servlet의 {@link Filter} 인터페이스를 구현하여
 * 모든 HTTP 요청이 컨트롤러에 도달하기 전에 인증 검사를 수행한다.
 * 토큰이 없거나 유효하지 않으면 userId 속성이 설정되지 않고 다음 필터로 넘어간다.
 * (인가 처리는 별도의 인터셉터나 AOP에서 수행할 수 있다)</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 Spring Security의 SecurityFilterChain에
 * 인증 필터를 등록하고, SecurityContext에 인증 정보를 저장한다. 세션 기반 인증도 가능하다.
 * MSA에서는 각 서비스가 독립적으로 토큰을 검증해야 하므로(Stateless),
 * JWT 같은 자체 검증 가능한 토큰이 필수적이다.
 * 이 필터가 공통 모듈에 있어 모든 서비스가 동일한 인증 로직을 사용한다.</p>
 */
@Component
@RequiredArgsConstructor
public class AuthenticationFilter implements Filter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        // Authorization 헤더에서 "Bearer " 접두사를 제거하고 토큰 추출
        String token = resolveToken(httpRequest);

        if (token != null && jwtTokenProvider.validateToken(token)) {
            // 토큰이 유효하면 userId를 요청 속성에 저장 -> 컨트롤러에서 사용
            Long userId = jwtTokenProvider.getUserId(token);
            httpRequest.setAttribute("userId", userId);
        }

        // 인증 성공/실패와 관계없이 다음 필터로 전달 (인가는 별도 처리)
        chain.doFilter(request, response);
    }

    /**
     * Authorization 헤더에서 Bearer 토큰을 추출하는 헬퍼 메서드.
     * "Bearer eyJhbGci..." 형식에서 "eyJhbGci..." 부분만 반환한다.
     */
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
