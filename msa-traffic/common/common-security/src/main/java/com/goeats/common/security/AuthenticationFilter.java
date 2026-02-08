package com.goeats.common.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * JWT 인증 필터 (Authentication Filter)
 *
 * <p>HTTP 요청의 Authorization 헤더에서 JWT 토큰을 추출하고 검증하는 서블릿 필터.
 * 유효한 토큰이면 userId를 요청 속성(attribute)에 설정하여
 * 컨트롤러에서 인증된 사용자 정보를 사용할 수 있게 함.</p>
 *
 * <h3>인증 흐름</h3>
 * <pre>
 *   Client → [Authorization: Bearer xxx] → AuthenticationFilter
 *     1. "Bearer " 접두사 제거하여 토큰 추출
 *     2. JwtTokenProvider.validateToken()으로 토큰 유효성 검증
 *     3. 유효하면 JwtTokenProvider.getUserId()로 사용자 ID 추출
 *     4. request.setAttribute("userId", userId) 설정
 *     5. chain.doFilter()로 다음 필터/컨트롤러로 전달
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 Spring Security FilterChain에 통합하여 세션 기반 또는
 * JWT 인증 처리. 단일 애플리케이션이므로 인증 로직이 한 곳에만 존재.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic과 동일한 구조. 각 마이크로서비스가 이 필터를 공유하여
 * 독립적으로 JWT 검증 수행. MSA-Traffic에서는 Gateway에서 1차 인증 후
 * 각 서비스에서 이 필터로 2차 검증하는 이중 인증 구조.</p>
 *
 * <h3>Gateway와의 관계 (MSA-Traffic)</h3>
 * <pre>
 *   Client → Gateway (1차 인증: Rate Limiting, JWT 검증)
 *          → 각 서비스 (2차 인증: AuthenticationFilter)
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class AuthenticationFilter implements Filter {

    private final JwtTokenProvider jwtTokenProvider; // JWT 토큰 생성/검증 제공자

    /**
     * 서블릿 필터 체인 처리.
     * 모든 HTTP 요청이 이 필터를 거치며, JWT 토큰이 있으면 검증 후 userId를 설정.
     * 토큰이 없거나 유효하지 않으면 인증 없이 다음 필터로 전달 (인가는 별도 처리).
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        // Authorization 헤더에서 JWT 토큰 추출
        String token = resolveToken(httpRequest);

        // 토큰이 존재하고 유효한 경우에만 사용자 정보 설정
        if (token != null && jwtTokenProvider.validateToken(token)) {
            Long userId = jwtTokenProvider.getUserId(token);
            httpRequest.setAttribute("userId", userId); // 컨트롤러에서 사용 가능
        }

        chain.doFilter(request, response); // 다음 필터 또는 컨트롤러로 전달
    }

    /**
     * Authorization 헤더에서 Bearer 토큰 추출.
     * "Bearer eyJhbGciOi..." → "eyJhbGciOi..." (접두사 제거)
     *
     * @param request HTTP 요청
     * @return JWT 토큰 문자열 (Bearer 헤더가 없으면 null)
     */
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7); // "Bearer " 이후의 토큰 부분만 추출
        }
        return null;
    }
}
