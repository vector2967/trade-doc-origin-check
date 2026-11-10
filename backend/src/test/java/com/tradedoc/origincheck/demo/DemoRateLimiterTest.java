package com.tradedoc.origincheck.demo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DemoRateLimiterTest {

    private final DemoRateLimiter limiter = new DemoRateLimiter(new DemoProperties(true, null, null, null, null, null, 3));

    @Test
    void IP_당_한_시간에_정해진_횟수까지() {
        Instant t = Instant.parse("2026-11-10T00:00:00Z");
        assertThat(limiter.tryAcquire("1.1.1.1", t)).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", t.plusSeconds(10))).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", t.plusSeconds(20))).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", t.plusSeconds(30))).isFalse();
        assertThat(limiter.tryAcquire("2.2.2.2", t.plusSeconds(30))).isTrue(); // 다른 IP 는 따로 센다
        // 첫 요청에서 1시간이 지나면 한 번 더 된다
        assertThat(limiter.tryAcquire("1.1.1.1", t.plus(Duration.ofHours(1)).plusSeconds(1))).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1", t.plus(Duration.ofHours(1)).plusSeconds(2))).isFalse();
    }
}
