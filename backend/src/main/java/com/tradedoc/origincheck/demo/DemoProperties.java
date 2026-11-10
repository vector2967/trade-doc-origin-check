package com.tradedoc.origincheck.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.demo.* — 체험하기(계정 없이 써 보기). 숫자는 모두 환경 변수로 바꿀 수 있다.
 *
 * @param enabled         false 면 체험 API(/auth/demo, /demo/samples…)가 404
 * @param tokenTtl        체험 토큰 유효 시간
 * @param dataTtl         체험 사용자·점검 건을 지우기까지의 시간(만든 시각부터)
 * @param perUserLimit    체험 사용자 한 명이 접수할 수 있는 점검 수(실패 건 포함, 다시 실행은 세지 않음)
 * @param dailyLimit      서비스 전체 하루(KST) 체험 점검 수
 * @param maxConcurrent   워커가 동시에 AI agent 에 맡겨 두는 체험 건 수. 정식 건은 따로 센다
 * @param tokensPerIpHour IP 하나가 한 시간에 체험 토큰을 받을 수 있는 횟수
 */
@ConfigurationProperties(prefix = "app.demo")
public record DemoProperties(
        Boolean enabled,
        Duration tokenTtl,
        Duration dataTtl,
        Integer perUserLimit,
        Integer dailyLimit,
        Integer maxConcurrent,
        Integer tokensPerIpHour
) {
    public DemoProperties {
        enabled = enabled == null || enabled;
        tokenTtl = tokenTtl == null ? Duration.ofHours(2) : tokenTtl;
        dataTtl = dataTtl == null ? Duration.ofHours(24) : dataTtl;
        perUserLimit = perUserLimit == null ? 3 : perUserLimit;
        dailyLimit = dailyLimit == null ? 50 : dailyLimit;
        maxConcurrent = maxConcurrent == null ? 2 : maxConcurrent;
        tokensPerIpHour = tokensPerIpHour == null ? 10 : tokensPerIpHour;
    }
}
