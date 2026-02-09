package com.goeats.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 토큰 제공자 (JWT Token Provider)
 *
 * <p>JWT(JSON Web Token) 생성, 검증, 파싱을 담당하는 컴포넌트.
 * HMAC-SHA256 알고리즘으로 토큰을 서명하며, 모든 마이크로서비스가
 * 동일한 시크릿 키를 공유하여 토큰을 상호 검증 가능.</p>
 *
 * <h3>토큰 구조 (JWT Claims)</h3>
 * <pre>
 *   Header:  {"alg": "HS256", "typ": "JWT"}
 *   Payload: {"sub": "123",          ← userId
 *             "iat": 1700000000,     ← 발급 시각
 *             "exp": 1700003600}     ← 만료 시각 (기본 1시간)
 *   Signature: HMACSHA256(header + "." + payload, secretKey)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 세션 기반 인증이 일반적. JWT를 사용하더라도
 * 단일 서버에서 토큰을 발급하고 검증하므로 키 공유 문제가 없음.
 * MSA에서는 여러 서비스가 같은 키로 토큰을 검증해야 하므로
 * 환경 변수(jwt.secret)로 키를 주입받는 구조가 필수.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic과 동일한 구조. MSA-Traffic에서는 Gateway에서 JWT를 먼저
 * 검증하고 유효한 요청만 하위 서비스로 라우팅. 하위 서비스에서도
 * 이 Provider로 2차 검증하여 보안을 이중으로 확보.</p>
 *
 * <h3>보안 주의사항</h3>
 * <p>기본 시크릿 키는 교육용이며, 프로덕션에서는 반드시 환경 변수로
 * 충분히 긴(256비트 이상) 랜덤 키를 주입해야 함.</p>
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;     // HMAC-SHA256 서명 키
    private final long expiration;   // 토큰 만료 시간 (밀리초, 기본 1시간=3600000ms)

    /**
     * 생성자: 시크릿 키와 만료 시간을 외부 설정에서 주입받음.
     * @Value 어노테이션으로 application.yml 또는 환경 변수에서 값을 읽음.
     *
     * @param secret     JWT 서명 키 (기본값은 교육용, 프로덕션에서는 환경 변수 필수)
     * @param expiration 토큰 만료 시간(ms) (기본 1시간)
     */
    public JwtTokenProvider(
            @Value("${jwt.secret:mySecretKeyForGoEatsMSAProjectThatIsLongEnough}") String secret,
            @Value("${jwt.expiration:3600000}") long expiration) {
        // 문자열 시크릿을 SecretKey 객체로 변환 (HMAC-SHA256 알고리즘 지정)
        this.key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.expiration = expiration;
    }

    /**
     * JWT 토큰 생성.
     * subject에 userId를 문자열로 저장하여 토큰에서 사용자를 식별.
     *
     * @param userId 사용자 ID
     * @return 서명된 JWT 토큰 문자열
     */
    public String createToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId)) // 사용자 ID를 subject 클레임에 저장
                .issuedAt(now)                    // 토큰 발급 시각
                .expiration(new Date(now.getTime() + expiration)) // 만료 시각
                .signWith(key)                    // HMAC-SHA256으로 서명
                .compact();                       // 최종 JWT 문자열 생성
    }

    /**
     * JWT 토큰에서 사용자 ID 추출.
     * 토큰의 subject 클레임을 Long으로 파싱하여 반환.
     *
     * @param token JWT 토큰 문자열
     * @return 사용자 ID
     */
    public Long getUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)          // 서명 검증 키 설정
                .build()
                .parseSignedClaims(token) // 토큰 파싱 + 서명 검증
                .getPayload();            // 클레임(페이로드) 추출
        return Long.parseLong(claims.getSubject()); // subject → userId
    }

    /**
     * JWT 토큰 유효성 검증.
     * 서명 위변조, 만료, 형식 오류 등을 확인.
     *
     * @param token JWT 토큰 문자열
     * @return 유효하면 true, 무효하면 false
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true; // 파싱 성공 = 유효한 토큰
        } catch (JwtException e) {
            return false; // 서명 불일치, 만료, 형식 오류 등
        }
    }
}
