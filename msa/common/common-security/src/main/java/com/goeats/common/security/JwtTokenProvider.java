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
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expiration;

    public JwtTokenProvider(
            @Value("${jwt.secret:mySecretKeyForGoEatsMSAProjectThatIsLongEnough}") String secret,
            @Value("${jwt.expiration:3600000}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expiration = expiration;
    }

    public String createToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    public Long getUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.parseLong(claims.getSubject());
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}
