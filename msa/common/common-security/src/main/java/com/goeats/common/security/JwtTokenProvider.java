package com.goeats.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * ★ MSA: JWT token verification shared across all microservices.
 * Each service includes common-security module and validates tokens independently.
 *
 * Compare with Monolithic: Session-based auth or single security filter,
 * no need for shared security module.
 */
/**
 * JWT 토큰 생성 및 검증 - MSA에서 각 서비스가 독립적으로 토큰을 검증하는 핵심 컴포넌트.
 *
 * <p>HMAC-SHA 알고리즘을 사용하여 대칭 키(symmetric key)로 토큰을 서명/검증한다.
 * 모든 서비스가 동일한 secret 키를 공유하므로, 어떤 서비스에서든 토큰 검증이 가능하다.</p>
 *
 * <p>토큰에 userId만 포함하는 이유: 토큰이 커지면 매 요청마다 네트워크 오버헤드가 증가한다.
 * 최소한의 정보(subject=userId)만 담고, 추가 정보가 필요하면 서비스 내부에서 조회한다.</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 서버 측 세션(HttpSession)으로 인증 상태를
 * 관리할 수 있다. 서버가 하나이므로 세션 동기화 문제가 없다.
 * MSA에서는 서비스가 여러 개이고, 각각 독립된 서버에서 동작하므로
 * 세션 공유가 어렵다(Sticky Session이나 Redis Session이 필요).
 * JWT는 토큰 자체에 검증 정보가 포함되어 있어(self-contained),
 * 별도의 세션 저장소 없이 어떤 서비스에서든 검증할 수 있다.</p>
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;       // HMAC-SHA 서명에 사용하는 비밀 키
    private final long expiration;     // 토큰 만료 시간 (밀리초)

    // secret과 expiration을 외부 설정(application.yml)에서 주입받음
    // 기본값은 개발 환경용 - 운영 환경에서는 반드시 환경 변수로 주입해야 한다
    public JwtTokenProvider(
            @Value("${jwt.secret:mySecretKeyForGoEatsMSAProjectThatIsLongEnough}") String secret,
            @Value("${jwt.expiration:3600000}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expiration = expiration;
    }

    /**
     * JWT 토큰 생성 - userId를 subject로 설정하고 서명한다.
     * 생성 시각(issuedAt)과 만료 시각(expiration)을 포함하여
     * 토큰의 유효 기간을 제한한다.
     */
    public String createToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    /**
     * 토큰에서 userId를 추출 - 서명 검증 후 subject(userId) 값을 반환한다.
     * 서명이 유효하지 않으면 JwtException이 발생한다.
     */
    public Long getUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 토큰 유효성 검증 - 서명, 만료 시각, 형식 등을 모두 검증한다.
     * 유효하면 true, 어떤 이유로든 실패하면 false를 반환한다.
     * (만료된 토큰, 변조된 토큰, 잘못된 형식 등 모두 false)
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}
