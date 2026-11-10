package com.tradedoc.origincheck.auth.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

/**
 * @param expiresAt 토큰 만료 시각 (UTC)
 * @param demo      체험 사용자면 true
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MeResponse(Long id, String email, String name, String company, Instant expiresAt, boolean demo) {
}
