package com.tradedoc.origincheck.demo;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 체험 토큰 발급 횟수 제한: IP 하나당 최근 1시간에 {@link DemoProperties#tokensPerIpHour()} 번.
 * 서버가 1대라 메모리에 둔다(재시작하면 비워진다).
 */
@Component
public class DemoRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final DemoProperties props;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public DemoRateLimiter(DemoProperties props) {
        this.props = props;
    }

    /** 한 번 쓴다. 한도를 넘었으면 false (쓰지 않는다) */
    public boolean tryAcquire(String ip, Instant now) {
        Deque<Instant> q = hits.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant from = now.minus(WINDOW);
            while (!q.isEmpty() && !q.peekFirst().isAfter(from)) {
                q.pollFirst();
            }
            if (q.size() >= props.tokensPerIpHour()) {
                return false;
            }
            q.addLast(now);
        }
        if (hits.size() > 10_000) { // 오래된 IP 정리(메모리 보호)
            for (Iterator<Map.Entry<String, Deque<Instant>>> it = hits.entrySet().iterator(); it.hasNext(); ) {
                Deque<Instant> d = it.next().getValue();
                synchronized (d) {
                    if (d.isEmpty() || !d.peekLast().isAfter(now.minus(WINDOW))) {
                        it.remove();
                    }
                }
            }
        }
        return true;
    }
}
