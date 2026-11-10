package com.tradedoc.origincheck.check;

import com.tradedoc.origincheck.agent.AgentException;
import com.tradedoc.origincheck.agent.AgentPoll;
import com.tradedoc.origincheck.agent.AgentProperties;
import com.tradedoc.origincheck.agent.AgentSubmission;
import com.tradedoc.origincheck.agent.CheckAgent;
import com.tradedoc.origincheck.agent.dto.AgentCheckResult;
import com.tradedoc.origincheck.agent.dto.AgentJudge;
import com.tradedoc.origincheck.agent.dto.AgentMismatch;
import com.tradedoc.origincheck.agent.dto.Decision;
import com.tradedoc.origincheck.check.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 워커의 상태 전환: QUEUED → IN_PROGRESS → COMPLETED / FAILED. DB·Upstage 없이 확인한다. */
class OriginCheckWorkerTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Map<Long, OriginCheck> db = new LinkedHashMap<>();
    private final StubAgent agent = new StubAgent();
    private OriginCheckWorker worker;

    @BeforeEach
    void setUp() {
        OriginCheckRepository repo = mock(OriginCheckRepository.class);
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.<Long>getArgument(0))));
        when(repo.findTop20ByStatusOrderByIdAsc(any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.getStatus() == inv.getArgument(0)).toList());
        when(repo.findTop20ByStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(any(), any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.getStatus() == inv.getArgument(0) && !c.getNextSubmitAt().isAfter(inv.getArgument(1))).toList());
        when(repo.findTop20ByDemoFalseAndStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(any(), any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> !c.isDemo() && c.getStatus() == inv.getArgument(0) && !c.getNextSubmitAt().isAfter(inv.getArgument(1))).toList());
        when(repo.findByDemoTrueAndStatusAndNextSubmitAtLessThanEqualOrderByIdAsc(any(), any(), any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.isDemo() && c.getStatus() == inv.getArgument(0) && !c.getNextSubmitAt().isAfter(inv.getArgument(1)))
                .sorted(java.util.Comparator.comparing(OriginCheck::getId))
                .limit(inv.<org.springframework.data.domain.Pageable>getArgument(2).getPageSize()).toList());
        when(repo.countByDemoTrueAndStatus(any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> c.isDemo() && c.getStatus() == inv.getArgument(0)).count());
        when(repo.findTop20ByStatusInAndAgentFileIdIsNotNullOrderByIdAsc(any())).thenAnswer(inv -> db.values().stream()
                .filter(c -> inv.<List<CheckStatus>>getArgument(0).contains(c.getStatus()) && c.getAgentFileId() != null).toList());
        FileStorage storage = mock(FileStorage.class);
        when(storage.path(any())).thenAnswer(inv -> Path.of("/files", inv.<String>getArgument(0)));
        AgentProperties props = new AgentProperties("test", null, Duration.ZERO, 1000, Duration.ofMinutes(30), 3, Duration.ofSeconds(10));
        worker = new OriginCheckWorker(repo, agent, storage, props, mapper, new TransactionTemplate(new NoopTxManager()),
                new com.tradedoc.origincheck.demo.DemoProperties(true, null, null, null, null, 2, null));
    }

    @Test
    void 접수된_건을_제출하면_in_progress와_작업_ID() {
        OriginCheck c = queued(1L);

        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);
        assertThat(c.getAgentJobId()).isEqualTo("job_1");
        assertThat(c.getAgentFileId()).isEqualTo("file_1");
        assertThat(c.getSubmittedAt()).isNotNull();
        assertThat(agent.submitted).containsExactly("/files/key-1|set04.pdf");
    }

    @Test
    void 제출이_실패하면_AGENT_ERROR() {
        OriginCheck c = queued(1L);
        agent.submitError = new AgentException("Upstage 파일 업로드 실패 (HTTP 403)");

        worker.tick();

        assertFailed(c, "AGENT_ERROR", "HTTP 403");
    }

    @Test
    void 제출_중_일시적_오류면_접수_상태로_두고_나중에_다시_제출한다() {
        OriginCheck c = queued(1L);
        agent.submitError = AgentException.transientError("Upstage 파일 업로드 실패: I/O error", null);

        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.QUEUED);
        assertThat(c.getSubmitAttempts()).isEqualTo(1);
        assertThat(c.getNextSubmitAt()).isAfter(Instant.now().plusSeconds(5));

        worker.tick(); // 대기 시간 전에는 다시 부르지 않는다
        assertThat(agent.submitCalls).isEqualTo(1);

        agent.submitError = null;
        ReflectionTestUtils.setField(c, "nextSubmitAt", Instant.now().minusSeconds(1));
        worker.tick();
        assertThat(c.getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);
        assertThat(agent.submitCalls).isEqualTo(2);
    }

    @Test
    void 일시적_오류가_최대_횟수까지_이어지면_AGENT_ERROR() {
        OriginCheck c = queued(1L);
        agent.submitError = AgentException.transientError("Upstage 파일 업로드 실패 (HTTP 503)", null);

        for (int i = 0; i < 3; i++) {
            ReflectionTestUtils.setField(c, "nextSubmitAt", Instant.now().minusSeconds(1));
            worker.tick();
        }

        assertFailed(c, "AGENT_ERROR", "3회 시도");
        assertThat(agent.submitCalls).isEqualTo(3);
    }

    @Test
    void 재시도_간격은_두_배씩_늘고_10분을_넘지_않는다() {
        AgentProperties p = new AgentProperties("x", null, null, 1000, Duration.ofMinutes(30), null, null);
        assertThat(p.submitMaxAttempts()).isEqualTo(5);
        assertThat(p.submitBackoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.submitBackoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(p.submitBackoff(4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(p.submitBackoff(30)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 라이브러리_누락_같은_Error도_실패로_끝내고_다시_제출하지_않는다() {
        OriginCheck c = queued(1L);
        agent.submitError = new NoClassDefFoundError("org/reactivestreams/Publisher");

        worker.tick();
        worker.tick();

        assertFailed(c, "AGENT_ERROR", "제출 중 오류");
        assertThat(agent.submitCalls).isEqualTo(1);
    }

    @Test
    void 처리_중이면_그대로_둔다() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.next = AgentPoll.running();

        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);
        assertThat(c.getCompletedAt()).isNull();
    }

    @Test
    void 완료되면_결과_JSON과_판정_송장번호를_저장한다() {
        OriginCheck c = inProgress(1L, Instant.now());
        AgentJudge judge = new AgentJudge("SM-2609-0044", Decision.SUPPLEMENT_REQUEST, "송장번호 다름",
                List.of("CO_INVOICE_NO_MISMATCH"), List.of());
        agent.next = AgentPoll.completed(new AgentCheckResult("job_1", judge,
                List.of(AgentMismatch.of("CO_INVOICE_NO_MISMATCH")), null, null, null, null));

        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.COMPLETED);
        assertThat(c.getDecision()).isEqualTo(Decision.SUPPLEMENT_REQUEST);
        assertThat(c.getInvoiceNo()).isEqualTo("SM-2609-0044");
        assertThat(c.getCompletedAt()).isNotNull();
        AgentCheckResult saved = mapper.readValue(c.getResultJson(), AgentCheckResult.class);
        assertThat(saved.judge()).isEqualTo(judge);
        assertThat(saved.mismatches().get(0).stage()).isEqualTo(2);
    }

    @Test
    void 판정_없이_완료되면_판정_컬럼은_비운다() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.next = AgentPoll.completed(new AgentCheckResult("job_1", null, null, null, null, null, "판정 노드 출력을 찾지 못함"));

        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.COMPLETED);
        assertThat(c.getDecision()).isNull();
        assertThat(c.getResultJson()).contains("판정 노드 출력을 찾지 못함");
    }

    @Test
    void agent가_실패로_끝나면_AGENT_FAILED() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.next = AgentPoll.failed("Upstage 작업이 failed 상태로 끝났습니다");

        worker.tick();

        assertFailed(c, "AGENT_FAILED", "failed 상태");
    }

    @Test
    void 조회_중_네트워크_오류면_다음_주기에_다시_본다() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.pollError = AgentException.transientError("Upstage 결과 조회 실패: timeout", null);

        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);

        agent.pollError = null;
        agent.next = AgentPoll.failed("x");
        worker.tick();
        assertThat(c.getStatus()).isEqualTo(CheckStatus.FAILED);
    }

    @Test
    void 조회가_4xx로_실패하면_시간_초과까지_기다리지_않고_AGENT_ERROR() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.pollError = new AgentException("Upstage 결과 조회 실패 (HTTP 404): response not found");

        worker.tick();

        assertFailed(c, "AGENT_ERROR", "HTTP 404");
        assertThat(agent.pollCalls).isEqualTo(1);
    }

    @Test
    void 결과를_해석하지_못하면_RESULT_PARSE_ERROR() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.pollError = new IllegalStateException("예상과 다른 형식");

        worker.tick();

        assertFailed(c, "RESULT_PARSE_ERROR", "예상과 다른 형식");
    }

    @Test
    void 제한_시간이_지나면_agent를_부르지_않고_AGENT_TIMEOUT() {
        OriginCheck c = inProgress(1L, Instant.now().minus(Duration.ofMinutes(31)));

        worker.tick();

        assertFailed(c, "AGENT_TIMEOUT", "30분");
        assertThat(agent.pollCalls).isZero();
    }

    @Test
    void 한_건의_오류가_다른_건_처리를_막지_않는다() {
        OriginCheck broken = inProgress(1L, Instant.now());
        ReflectionTestUtils.setField(broken, "submittedAt", null);
        ReflectionTestUtils.setField(broken, "createdAt", null); // 시각 계산에서 NPE
        OriginCheck ok = inProgress(2L, Instant.now());
        agent.next = AgentPoll.failed("x");

        worker.tick();

        assertThat(broken.getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);
        assertThat(ok.getStatus()).isEqualTo(CheckStatus.FAILED);
    }

    @Test
    void 완료되면_agent_파일을_지운다() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.next = AgentPoll.completed(new AgentCheckResult("job_1", null, null, null, null, null, null));

        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.COMPLETED);
        assertThat(agent.deleted).containsExactly("file_1");
        assertThat(c.getAgentFileId()).isNull();
    }

    @Test
    void 실패로_끝나도_agent_파일을_지운다() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.next = AgentPoll.failed("x");

        worker.tick();

        assertThat(agent.deleted).containsExactly("file_1");
        assertThat(c.getAgentFileId()).isNull();
    }

    @Test
    void 파일_삭제가_실패하면_다음_주기에_다시_지운다() {
        OriginCheck c = inProgress(1L, Instant.now());
        agent.next = AgentPoll.failed("x");
        agent.deleteError = new AgentException("Upstage 파일 삭제 실패 (HTTP 500)");

        worker.tick();
        assertThat(c.getAgentFileId()).isEqualTo("file_1");

        agent.deleteError = null;
        worker.tick();
        assertThat(c.getAgentFileId()).isNull();
        assertThat(agent.deleted).containsExactly("file_1");
    }

    @Test
    void 다시_실행한_건은_이전_agent_파일을_먼저_지우고_다음_주기에_제출한다() {
        OriginCheck c = inProgress(1L, Instant.now());
        c.markFailed("AGENT_FAILED", "x");
        c.resetForRetry();
        agent.deleteError = new AgentException("Upstage 파일 삭제 실패 (HTTP 500)");

        worker.tick(); // 삭제 실패 → 제출하지 않는다
        assertThat(agent.submitCalls).isZero();
        assertThat(c.getAgentFileId()).isEqualTo("file_1");

        agent.deleteError = null;
        worker.tick(); // 삭제
        assertThat(agent.deleted).containsExactly("file_1");
        worker.tick(); // 제출
        assertThat(c.getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);
        assertThat(agent.submitCalls).isEqualTo(1);
    }

    @Test
    void 올린_파일이_없으면_지우지_않는다() {
        OriginCheck c = queued(1L);
        agent.fileIds = false; // fake 모드처럼 파일 ID 가 없다
        worker.tick();
        agent.next = AgentPoll.failed("x");
        worker.tick();

        assertThat(c.getStatus()).isEqualTo(CheckStatus.FAILED);
        assertThat(agent.deleteCalls).isZero();
    }

    private void assertFailed(OriginCheck c, String code, String messagePart) {
        assertThat(c.getStatus()).isEqualTo(CheckStatus.FAILED);
        assertThat(c.getErrorCode()).isEqualTo(code);
        assertThat(c.getErrorMessage()).contains(messagePart);
        assertThat(c.getCompletedAt()).isNotNull();
    }

    @Test
    void 체험_건은_동시에_2건까지만_제출하고_정식_건은_막지_않는다() {
        for (long id = 1; id <= 3; id++) {
            queued(id).markDemo();
        }
        queued(4L); // 정식 건
        worker.tick();

        assertThat(db.get(1L).getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);
        assertThat(db.get(2L).getStatus()).isEqualTo(CheckStatus.IN_PROGRESS);
        assertThat(db.get(3L).getStatus()).isEqualTo(CheckStatus.QUEUED);      // 자리가 없어 기다린다
        assertThat(db.get(4L).getStatus()).isEqualTo(CheckStatus.IN_PROGRESS); // 정식 건은 바로

        worker.tick(); // 아직 두 건이 처리 중이라 그대로
        assertThat(db.get(3L).getStatus()).isEqualTo(CheckStatus.QUEUED);
    }

    private OriginCheck queued(Long id) {
        OriginCheck c = new OriginCheck(10L, "제목", "set04.pdf", "key-" + id, 100);
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "createdAt", Instant.now());
        ReflectionTestUtils.setField(c, "nextSubmitAt", Instant.now());
        db.put(id, c);
        return c;
    }

    private OriginCheck inProgress(Long id, Instant submittedAt) {
        OriginCheck c = queued(id);
        c.markSubmitted("job_" + id, "file_" + id);
        ReflectionTestUtils.setField(c, "submittedAt", submittedAt);
        return c;
    }

    static class StubAgent implements CheckAgent {
        final List<String> submitted = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        Throwable submitError;
        RuntimeException pollError;
        RuntimeException deleteError;
        AgentPoll next = AgentPoll.running();
        boolean fileIds = true;
        int submitCalls;
        int pollCalls;
        int deleteCalls;

        @Override
        public AgentSubmission submit(Path pdf, String fileName) {
            submitCalls++;
            if (submitError instanceof RuntimeException re) throw re;
            if (submitError instanceof Error err) throw err;
            submitted.add(pdf + "|" + fileName);
            int n = submitted.size();
            return new AgentSubmission("job_" + n, fileIds ? "file_" + n : null);
        }

        @Override
        public void deleteFile(String fileId) {
            deleteCalls++;
            if (deleteError != null) throw deleteError;
            deleted.add(fileId);
        }

        @Override
        public AgentPoll poll(String jobId) {
            pollCalls++;
            if (pollError != null) throw pollError;
            return next;
        }
    }

    /** TransactionTemplate 이 콜백만 실행하게 하는 빈 트랜잭션 매니저 */
    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
