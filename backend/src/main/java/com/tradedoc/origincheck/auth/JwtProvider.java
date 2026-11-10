package com.tradedoc.origincheck.auth;

import com.tradedoc.origincheck.common.ApiException;
import com.tradedoc.origincheck.common.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * HS256 JWT 발급·검증. 클레임: sub(사용자 ID), email, iss, iat, exp, 체험 사용자면 demo=true. refresh token 은 없다.
 */
@Component
public class JwtProvider {

    private static final String EMAIL_CLAIM = "email";
    private static final String DEMO_CLAIM = "demo";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtProvider(JwtProperties props) {
        this.props = props;
        byte[] secret = props.secret() == null ? new byte[0] : props.secret().getBytes(StandardCharsets.UTF_8);
        this.key = secret.length >= 32 ? Keys.hmacShaKeyFor(secret) : null;
    }

    public long expiresInSeconds() {
        return props.expiresMin() * 60;
    }

    public String issue(Long userId, String email) {
        return issue(userId, email, false, expiresInSeconds());
    }

    /** @param ttlSeconds 유효 시간(초). 체험 토큰은 정식 토큰과 길이가 다르다 */
    public String issue(Long userId, String email, boolean demo, long ttlSeconds) {
        Instant now = Instant.now();
        var b = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(EMAIL_CLAIM, email)
                .issuer(props.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (demo) {
            b.claim(DEMO_CLAIM, true);
        }
        return b.signWith(requireKey(), Jwts.SIG.HS256).compact();
    }

    /** @throws ApiException UNAUTHORIZED · TOKEN_EXPIRED · AUTH_NOT_CONFIGURED */
    public AuthUser verify(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(requireKey()) // HS256 이외(alg=none 포함)는 거절된다
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (c.getSubject() == null || c.getIssuedAt() == null || c.getExpiration() == null) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "토큰이 유효하지 않습니다.");
            }
            return new AuthUser(Long.valueOf(c.getSubject()), c.get(EMAIL_CLAIM, String.class),
                    c.getExpiration().toInstant(), Boolean.TRUE.equals(c.get(DEMO_CLAIM, Boolean.class)));
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "토큰이 유효하지 않습니다.");
        }
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new ApiException(ErrorCode.AUTH_NOT_CONFIGURED);
        }
        return key;
    }
}
