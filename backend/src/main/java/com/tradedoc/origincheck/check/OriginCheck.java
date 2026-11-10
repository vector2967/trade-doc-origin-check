package com.tradedoc.origincheck.check;

import com.tradedoc.origincheck.agent.dto.Decision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 원산지 점검 1건(화면의 "심사"). 원본 PDF 는 파일 저장소에, agent 결과 전체는 result_json 에 둔다.
 * 목록에 보이는 값(decision, invoice_no, 요약 5개)만 따로 컬럼으로 뺐다.
 */
@Entity
@Table(name = "origin_checks")
public class OriginCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 체험 사용자가 접수한 건. 워커가 동시 제출 수를 따로 묶는다 */
    @Column(nullable = false)
    private boolean demo;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_key", nullable = false)
    private String fileKey;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private CheckStatus status;

    @Column(name = "agent_job_id", length = 100)
    private String agentJobId;

    /** agent 쪽에 올린 파일. 처리가 끝나면 지우고 null 로 둔다 */
    @Column(name = "agent_file_id", length = 100)
    private String agentFileId;

    /** 일시적 오류로 실패한 제출 시도 수 */
    @Column(name = "submit_attempts", nullable = false)
    private int submitAttempts;

    /** QUEUED 인 건은 이 시각 이후에 제출한다(재시도 대기) */
    @Column(name = "next_submit_at")
    private Instant nextSubmitAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private Decision decision;

    @Column(name = "invoice_no", length = 100)
    private String invoiceNo;

    // 목록 요약(CheckSummary). 완료될 때 추출 결과에서 뽑아 둔다
    @Column(length = 255)
    private String consignee;

    @Column(name = "primary_item", length = 255)
    private String primaryItem;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "destination_country", length = 100)
    private String destinationCountry;

    @Column(name = "shipped_on")
    private LocalDate shippedOn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json")
    private String resultJson;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** 담당자가 확인을 마치고 완료 처리한 시각. null 이면 진행 중 */
    @Column(name = "done_at")
    private Instant doneAt;

    protected OriginCheck() {
    }

    public OriginCheck(Long userId, String title, String fileName, String fileKey, long fileSize) {
        this.userId = userId;
        this.title = title;
        this.fileName = fileName;
        this.fileKey = fileKey;
        this.fileSize = fileSize;
        this.status = CheckStatus.QUEUED;
    }

    @PrePersist
    void prePersist() {
        createdAt = now();
        updatedAt = createdAt;
        nextSubmitAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = now();
    }

    public void markDemo() {
        this.demo = true;
    }

    public boolean isDemo() {
        return demo;
    }

    public void markSubmitted(String agentJobId, String agentFileId) {
        this.agentJobId = agentJobId;
        this.agentFileId = agentFileId;
        this.status = CheckStatus.IN_PROGRESS;
        this.submittedAt = now();
    }

    public void markCompleted(String resultJson, Decision decision, String invoiceNo, CheckSummary summary) {
        this.resultJson = resultJson;
        this.decision = decision;
        // 컬럼(100자)보다 긴 값이 오면 저장이 실패해 같은 건을 계속 다시 처리하게 되므로 자른다(전체 값은 result_json 에 있다)
        this.invoiceNo = invoiceNo == null || invoiceNo.length() <= 100 ? invoiceNo : invoiceNo.substring(0, 100);
        applySummary(summary == null ? CheckSummary.EMPTY : summary);
        this.status = CheckStatus.COMPLETED;
        this.completedAt = now();
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage == null || errorMessage.length() <= 1000
                ? errorMessage : errorMessage.substring(0, 1000);
        this.status = CheckStatus.FAILED;
        this.completedAt = now();
    }

    /** 처리(agent)가 끝난 건인가. 처리 중인 건은 완료 처리할 수 없다 */
    public boolean isFinished() {
        return status == CheckStatus.COMPLETED || status == CheckStatus.FAILED;
    }

    /** 담당자 완료 처리. 이미 완료면 처음 시각을 유지한다 */
    public void markDone() {
        if (doneAt == null) {
            doneAt = now();
        }
    }

    /** 완료 처리를 되돌린다 (진행 중으로) */
    public void reopen() {
        doneAt = null;
    }

    /**
     * 실패한 건을 다시 실행한다: 접수(QUEUED) 상태로 되돌리고 이전 작업·결과·오류를 지운다.
     * agent 쪽 파일(agentFileId)은 그대로 둔다 → 워커가 먼저 지운 뒤 다시 제출한다.
     */
    public void resetForRetry() {
        this.status = CheckStatus.QUEUED;
        this.agentJobId = null;
        this.resultJson = null;
        this.decision = null;
        this.invoiceNo = null;
        applySummary(CheckSummary.EMPTY);
        this.errorCode = null;
        this.errorMessage = null;
        this.submittedAt = null;
        this.completedAt = null;
        this.doneAt = null;
        this.submitAttempts = 0;
        this.nextSubmitAt = now();
    }

    /** 제출이 일시적 오류로 실패했다. QUEUED 로 두고 {@code next} 이후에 다시 제출한다 */
    public void deferSubmit(Instant next) {
        this.submitAttempts++;
        this.nextSubmitAt = next;
    }

    public int getSubmitAttempts() {
        return submitAttempts;
    }

    public Instant getNextSubmitAt() {
        return nextSubmitAt;
    }

    /** agent 쪽 파일을 지웠다 */
    public void clearAgentFile() {
        this.agentFileId = null;
    }

    public String getAgentFileId() {
        return agentFileId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileKey() {
        return fileKey;
    }

    public long getFileSize() {
        return fileSize;
    }

    public CheckStatus getStatus() {
        return status;
    }

    public String getAgentJobId() {
        return agentJobId;
    }

    public Decision getDecision() {
        return decision;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    private void applySummary(CheckSummary s) {
        this.consignee = s.consignee();
        this.primaryItem = s.primaryItem();
        this.itemCount = s.itemCount();
        this.destinationCountry = s.destinationCountry();
        this.shippedOn = s.shippedOn();
    }

    public String getConsignee() {
        return consignee;
    }

    public String getPrimaryItem() {
        return primaryItem;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public String getDestinationCountry() {
        return destinationCountry;
    }

    public LocalDate getShippedOn() {
        return shippedOn;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    /** DB(DATETIME(6))에 저장되는 정밀도(마이크로초)에 맞춘 지금 시각. 리눅스 JVM 은 나노초까지 주므로 응답 값과 다시 읽은 값이 달라진다 */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
