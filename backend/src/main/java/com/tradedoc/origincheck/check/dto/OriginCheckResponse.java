package com.tradedoc.origincheck.check.dto;

import com.tradedoc.origincheck.agent.dto.AgentCheckResult;
import com.tradedoc.origincheck.agent.dto.PlainText;
import com.tradedoc.origincheck.agent.dto.AgentExtracted;
import com.tradedoc.origincheck.agent.dto.AgentFollowUp;
import com.tradedoc.origincheck.agent.dto.AgentJudge;
import com.tradedoc.origincheck.agent.dto.AgentMismatch;
import com.tradedoc.origincheck.agent.dto.meta.AgentExtractedMeta;
import com.tradedoc.origincheck.check.CheckStatus;
import com.tradedoc.origincheck.check.OriginCheck;
import com.tradedoc.origincheck.check.OriginCheckFile;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/origin-checks/{id} 응답. 점검 건 정보 + agent 결과(AgentCheckResult)를 한 번에 준다.
 * status 가 completed 가 아니면 judge · follow_up 은 null, 목록은 빈 값이다.
 * judge.reason · follow_up.text 는 내보낼 때 내부 코드·JSON 키를 사람 말로 바꾼다(PlainText). DB 에는 원문이 남는다.
 *
 * @param doneAt 담당자 완료 처리 시각 (진행 중이면 null)
 * @param jobId agent 작업 ID (제출 전이면 null)
 * @param files 올린 원본 파일들과 이어 붙인 PDF 에서의 쪽 범위 (올린 순서. 이 기능 전에 만든 건은 빈 목록)
 * @param error 실패(failed)거나 결과 일부가 빠졌을 때. 없으면 null
 * @param queuePosition 체험 건이 접수 대기(queued) 중이면 체험 대기열 순번(1 = 다음 차례). 정식 건이거나 대기 중이 아니면 null
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OriginCheckResponse(
        Long id,
        String title,
        String fileName,
        CheckStatus status,
        Integer queuePosition,
        Instant createdAt,
        Instant completedAt,
        Instant doneAt,
        String jobId,
        AgentJudge judge,
        List<AgentMismatch> mismatches,
        AgentFollowUp followUp,
        AgentExtracted extracted,
        AgentExtractedMeta extractedMeta,
        List<CheckFileResponse> files,
        CheckError error
) {
    public static OriginCheckResponse of(OriginCheck c, AgentCheckResult r, List<OriginCheckFile> files) {
        return of(c, r, files, null);
    }

    public static OriginCheckResponse of(OriginCheck c, AgentCheckResult r, List<OriginCheckFile> files, Integer queuePosition) {
        CheckError error = c.getErrorCode() != null ? new CheckError(c.getErrorCode(), c.getErrorMessage())
                : r != null && r.error() != null ? new CheckError("RESULT_INCOMPLETE", r.error())
                : null;
        return new OriginCheckResponse(c.getId(), c.getTitle(), c.getFileName(), c.getStatus(), queuePosition,
                c.getCreatedAt(), c.getCompletedAt(), c.getDoneAt(), c.getAgentJobId(),
                r == null || r.judge() == null ? null : r.judge().withReason(PlainText.humanize(r.judge().reason())),
                r == null ? List.of() : r.mismatches(),
                r == null || r.followUp() == null ? null
                        : new AgentFollowUp(r.followUp().kind(), PlainText.humanize(r.followUp().text())),
                r == null ? AgentExtracted.empty() : r.extracted(),
                r == null ? AgentExtractedMeta.empty() : r.extractedMeta(),
                files.stream().map(CheckFileResponse::from).toList(),
                error);
    }
}
