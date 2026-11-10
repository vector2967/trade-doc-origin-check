package com.tradedoc.origincheck.auth;

import java.time.Instant;

/** 토큰에서 꺼낸 로그인 사용자. 컨트롤러에서 @AuthenticationPrincipal 로 받는다. */
public record AuthUser(Long id, String email, Instant expiresAt, boolean demo) {

    public AuthUser(Long id, String email, Instant expiresAt) {
        this(id, email, expiresAt, false);
    }
}
