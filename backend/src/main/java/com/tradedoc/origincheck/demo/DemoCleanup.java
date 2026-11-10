package com.tradedoc.origincheck.demo;

import com.tradedoc.origincheck.agent.AgentException;
import com.tradedoc.origincheck.agent.CheckAgent;
import com.tradedoc.origincheck.check.OriginCheck;
import com.tradedoc.origincheck.check.OriginCheckFileRepository;
import com.tradedoc.origincheck.check.OriginCheckRepository;
import com.tradedoc.origincheck.check.storage.FileStorage;
import com.tradedoc.origincheck.user.User;
import com.tradedoc.origincheck.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 만료된 체험 사용자 정리(기본 1시간마다): expires_at 이 지난 체험 사용자와 그 점검 건(원본 파일 목록 포함),
 * 저장소의 원본·합친 PDF, AI agent 쪽 파일을 지운다.
 * agent 파일을 못 지운 사용자는 건드리지 않고 다음 차례에 다시 한다(파일이 agent 쪽에 남지 않게).
 */
@Component
public class DemoCleanup {

    private static final Logger log = LoggerFactory.getLogger(DemoCleanup.class);

    private final UserRepository users;
    private final OriginCheckRepository checks;
    private final OriginCheckFileRepository checkFiles;
    private final FileStorage storage;
    private final CheckAgent agent;
    private final TransactionTemplate tx;

    public DemoCleanup(UserRepository users, OriginCheckRepository checks, OriginCheckFileRepository checkFiles,
                       FileStorage storage, CheckAgent agent, TransactionTemplate tx) {
        this.users = users;
        this.checks = checks;
        this.checkFiles = checkFiles;
        this.storage = storage;
        this.agent = agent;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${app.demo.cleanup-interval-ms:3600000}",
            initialDelayString = "${app.demo.cleanup-initial-delay-ms:60000}")
    public void run() {
        List<User> expired = users.findTop50ByDemoTrueAndExpiresAtBeforeOrderByIdAsc(Instant.now());
        int done = 0;
        for (User u : expired) {
            try {
                if (purge(u.getId())) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("체험 사용자 #{} 정리 중 오류(다음 차례에 다시)", u.getId(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("만료된 체험 사용자 {}명 중 {}명 정리", expired.size(), done);
        }
    }

    /** @return 지웠으면 true. agent 파일을 못 지웠으면 false(아무것도 지우지 않음) */
    boolean purge(Long userId) {
        List<OriginCheck> mine = checks.findAllByUserId(userId);
        // 1) agent 쪽 파일부터(바깥 시스템이라 트랜잭션으로 되돌릴 수 없다)
        for (OriginCheck c : mine) {
            if (c.getAgentFileId() == null) {
                continue;
            }
            try {
                agent.deleteFile(c.getAgentFileId());
            } catch (AgentException e) {
                log.warn("체험 사용자 #{} 점검 #{} agent 파일 {} 삭제 실패(다음 차례에 다시): {}",
                        userId, c.getId(), c.getAgentFileId(), e.getMessage());
                return false;
            }
        }
        // 2) DB: 점검 건(원본 파일 목록은 FK CASCADE) → 사용자
        Set<String> keys = new LinkedHashSet<>();
        tx.executeWithoutResult(s -> {
            for (OriginCheck c : checks.findAllByUserId(userId)) {
                keys.add(c.getFileKey());
                checkFiles.findByCheckIdOrderBySeqAsc(c.getId()).forEach(f -> keys.add(f.getFileKey()));
                checks.delete(c);
            }
            users.deleteById(userId);
        });
        // 3) 저장소 파일은 DB 삭제가 확정된 뒤에
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("체험 사용자 #{} 원본 파일 {} 삭제 실패: {}", userId, key, e.getMessage());
            }
        }
        return true;
    }
}
