package com.tradedoc.origincheck.check;

import com.tradedoc.origincheck.agent.AgentException;
import com.tradedoc.origincheck.agent.AgentPoll;
import com.tradedoc.origincheck.agent.AgentProperties;
import com.tradedoc.origincheck.agent.AgentSubmission;
import com.tradedoc.origincheck.agent.CheckAgent;
import com.tradedoc.origincheck.agent.dto.AgentCheckResult;
import com.tradedoc.origincheck.check.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import com.tradedoc.origincheck.demo.DemoProperties;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * 점검 건을 AI agent 로 처리한다. app.agent.poll-interval-ms 마다 돈다.
 * <ol>
 *   <li>QUEUED → PDF 를 agent 에 제출하고 IN_PROGRESS</li>
 *   <li>IN_PROGRESS → agent 상태 조회. 끝났으면 결과 전체를 저장하고 COMPLETED / FAILED</li>
 *   <li>끝난 건 → agent 쪽에 올린 파일 삭제 (Upstage 는 지울 때까지 보관한다)</li>
 * </ol>
 * 진행 상태를 DB 의 agent_job_id 로 추적하므로 서버를 재시작해도 이어서 처리한다.
 * agent HTTP 호출은 트랜잭션 밖에서 하고, 상태 변경만 짧은 트랜잭션으로 저장한다.
 * 서버를 여러 대 띄우면 같은 건을 두 번 제출할 수 있으니, 그때는 행 잠금(SELECT … FOR UPDATE SKIP LOCKED) 등이 필요하다.
 */
@Component
public class OriginCheckWorker {

    private static final Logger log = LoggerFactory.getLogger(OriginCheckWorker.class);

    private final OriginCheckRepository checks;
    private final CheckAgent agent;
    private final FileStorage storage;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    private final DemoProperties demo;

    public OriginCheckWorker(OriginCheckRepository checks, CheckAgent agent, FileStorage storage,
                             AgentProperties props, ObjectMapper objectMapper, TransactionTemplate tx, DemoProperties demo) {
        this.demo = demo;
        this.checks = checks;
        this.agent = agent;
        this.storage = storage;
        this.props = props;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${app.agent.poll-interval-ms:5000}", initialDelayString = "${app.agent.initial-delay-ms:3000}")
    public void tick() {
        Instant now = Instant.now();
        checks.findTop20ByDemoFalseAndStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(CheckStatus.QUEUED, now)
                .forEach(c -> safely(c, this::submit));
        // 체험 건은 동시에 agent 에 맡겨 두는 수를 따로 묶는다 → 체험이 몰려도 정식 건은 밀리지 않는다
        long free = demo.maxConcurrent() - checks.countByDemoTrueAndStatus(CheckStatus.IN_PROGRESS);
        if (free > 0) {
            checks.findByDemoTrueAndStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(CheckStatus.QUEUED, now,
                    PageRequest.of(0, (int) Math.min(free, 20))).forEach(c -> safely(c, this::submit));
        }
        checks.findTop20ByStatusOrderByIdAsc(CheckStatus.IN_PROGRESS).forEach(c -> safely(c, this::poll));
        checks.findTop20ByStatusInAndAgentFileIdIsNotNullOrderByIdAsc(FINISHED).forEach(c -> safely(c, this::cleanup));
    }

    /** agent 쪽 파일을 지울 상태. QUEUED 는 다시 실행한 건이 이전 파일을 아직 갖고 있는 경우 */
    private static final List<CheckStatus> FINISHED = List.of(CheckStatus.COMPLETED, CheckStatus.FAILED, CheckStatus.QUEUED);

    /** 끝난 건의 agent 쪽 파일을 지운다. 실패하면 다음 주기에 다시 한다(원본 PDF 는 우리 저장소에 남아 있다). */
    void cleanup(OriginCheck c) {
        try {
            agent.deleteFile(c.getAgentFileId());
            update(c.getId(), OriginCheck::clearAgentFile);
            log.info("점검 #{} agent 파일 {} 삭제", c.getId(), c.getAgentFileId());
        } catch (AgentException e) {
            log.warn("점검 #{} agent 파일 삭제 실패(다음 주기에 재시도): {}", c.getId(), e.getMessage());
        }
    }

    /** 한 건의 오류(DB 저장 실패 등)가 나머지 건 처리를 막지 않게 한다. */
    private void safely(OriginCheck c, Consumer<OriginCheck> step) {
        try {
            step.accept(c);
        } catch (RuntimeException e) {
            log.error("점검 #{} 처리 중 오류(다음 주기에 재시도)", c.getId(), e);
        }
    }

    void submit(OriginCheck c) {
        if (c.getAgentFileId() != null) {
            return; // 다시 실행한 건: 이전 agent 파일을 cleanup 이 지운 뒤에 제출한다(덮어쓰면 파일이 남는다)
        }
        try {
            AgentSubmission sub = agent.submit(storage.path(c.getFileKey()), c.getFileName());
            update(c.getId(), x -> x.markSubmitted(sub.jobId(), sub.fileId()));
            log.info("점검 #{} 제출 → agent 작업 {}", c.getId(), sub.jobId());
        } catch (AgentException e) {
            int failures = c.getSubmitAttempts() + 1;
            if (e.isRetryable() && failures < props.submitMaxAttempts()) {
                // 연결 끊김·5xx·429: 잠시 뒤 다시 제출한다(맥 잠자기·네트워크 순단 등)
                Duration wait = props.submitBackoff(failures);
                log.warn("점검 #{} 제출 실패 {}/{}회, {}초 뒤 재시도: {}", c.getId(), failures, props.submitMaxAttempts(),
                        wait.toSeconds(), e.getMessage());
                update(c.getId(), x -> x.deferSubmit(Instant.now().plus(wait)));
                return;
            }
            log.warn("점검 #{} 제출 실패({}회 시도): {}", c.getId(), failures, e.getMessage());
            String msg = failures > 1 ? e.getMessage() + " (" + failures + "회 시도)" : e.getMessage();
            update(c.getId(), x -> x.markFailed("AGENT_ERROR", msg));
        } catch (RuntimeException | LinkageError e) {
            // LinkageError: 라이브러리 누락(NoClassDefFoundError) 등. 잡지 않으면 같은 건을 끝없이 다시 제출한다
            log.error("점검 #{} 제출 중 오류", c.getId(), e);
            update(c.getId(), x -> x.markFailed("AGENT_ERROR", "AI agent 제출 중 오류가 발생했습니다."));
        }
    }

    void poll(OriginCheck c) {
        Instant since = c.getSubmittedAt() == null ? c.getCreatedAt() : c.getSubmittedAt();
        if (Instant.now().isAfter(since.plus(props.timeout()))) {
            update(c.getId(), x -> x.markFailed("AGENT_TIMEOUT", "AI agent 가 " + props.timeout().toMinutes() + "분 안에 끝나지 않았습니다."));
            return;
        }
        AgentPoll p;
        try {
            p = agent.poll(c.getAgentJobId());
        } catch (AgentException e) {
            if (!e.isRetryable()) {
                // 4xx(작업이 없음·권한 없음 등): 다시 물어도 같으니 시간 초과까지 기다리지 않고 끝낸다
                log.warn("점검 #{} 조회 실패(재시도 안 함): {}", c.getId(), e.getMessage());
                update(c.getId(), x -> x.markFailed("AGENT_ERROR", e.getMessage()));
                return;
            }
            // 연결 끊김·5xx·429: 실패 처리하지 않고 다음 주기에 다시 본다(시간 초과로 끝난다)
            log.warn("점검 #{} 조회 실패(다음 주기에 재시도): {}", c.getId(), e.getMessage());
            return;
        } catch (RuntimeException | LinkageError e) {
            // 응답을 결과로 바꾸지 못함(형식이 예상과 다름). 다시 해도 같으므로 실패로 끝낸다
            log.error("점검 #{} 결과 해석 실패", c.getId(), e);
            update(c.getId(), x -> x.markFailed("RESULT_PARSE_ERROR", "AI agent 결과를 해석하지 못했습니다: " + e.getMessage()));
            return;
        }
        switch (p.state()) {
            case RUNNING -> {
            }
            case COMPLETED -> {
                AgentCheckResult r = p.result();
                String json = objectMapper.writeValueAsString(r);
                CheckSummary summary = CheckSummary.from(r);
                update(c.getId(), x -> x.markCompleted(json,
                        r.judge() == null ? null : r.judge().decision(),
                        r.judge() == null ? null : r.judge().invoiceNo(),
                        summary));
                log.info("점검 #{} 완료: {}", c.getId(), r.judge() == null ? "판정 없음" : r.judge().decision().label());
            }
            case FAILED -> {
                update(c.getId(), x -> x.markFailed("AGENT_FAILED", p.error()));
                log.warn("점검 #{} 실패: {}", c.getId(), p.error());
            }
        }
    }

    private void update(Long id, Consumer<OriginCheck> change) {
        tx.executeWithoutResult(s -> checks.findById(id).ifPresent(change));
    }
}
