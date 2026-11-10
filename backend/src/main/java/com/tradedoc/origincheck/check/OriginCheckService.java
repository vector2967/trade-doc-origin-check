package com.tradedoc.origincheck.check;

import com.tradedoc.origincheck.agent.AgentException;
import com.tradedoc.origincheck.agent.CheckAgent;
import com.tradedoc.origincheck.agent.dto.AgentCheckResult;
import com.tradedoc.origincheck.check.dto.OriginCheckCreatedResponse;
import com.tradedoc.origincheck.check.dto.OriginCheckResponse;
import com.tradedoc.origincheck.check.dto.OriginCheckSummaryResponse;
import com.tradedoc.origincheck.check.storage.FileStorage;
import com.tradedoc.origincheck.check.storage.PdfBundler;
import com.tradedoc.origincheck.common.ApiException;
import com.tradedoc.origincheck.common.ErrorCode;
import com.tradedoc.origincheck.common.PageResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import com.tradedoc.origincheck.demo.DemoUploadGuard;

@Service
public class OriginCheckService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OriginCheckService.class);

    private static final Set<String> PDF_TYPES = Set.of("application/pdf", "application/octet-stream");
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};
    static final int MAX_FILES = 20;
    static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    /** 화면이 보내는 서류 종류 짐작 값 (RequiredDocument + 패킹리스트) */
    private static final Set<String> DOC_TYPES = Set.of("인보이스", "패킹리스트", "B/L", "원산지증명서", "원산지확인서", "수출신고필증");

    private final OriginCheckRepository checks;
    private final OriginCheckFileRepository checkFiles;
    private final FileStorage storage;
    private final PdfBundler pdf;
    private final ObjectMapper objectMapper;
    private final CheckAgent agent;
    private final DemoUploadGuard demoGuard;

    public OriginCheckService(OriginCheckRepository checks, OriginCheckFileRepository checkFiles, FileStorage storage,
                              PdfBundler pdf, ObjectMapper objectMapper, CheckAgent agent, DemoUploadGuard demoGuard) {
        this.demoGuard = demoGuard;
        this.checks = checks;
        this.checkFiles = checkFiles;
        this.storage = storage;
        this.pdf = pdf;
        this.objectMapper = objectMapper;
        this.agent = agent;
    }

    /**
     * 한 선적 건의 PDF 들을 저장하고 점검 건을 QUEUED 로 만든다. agent 제출은 {@link OriginCheckWorker} 가 한다.
     * 파일이 여러 개면 올린 순서대로 PDF 하나로 이어 붙여 그 PDF 로 점검한다(워크플로는 그대로). 원본은 파일별로 보관한다.
     *
     * @param hints 파일과 같은 순서의 서류 종류 짐작(파일명 기준, 화면이 보냄). 없거나 모르는 값은 null 로 둔다
     */
    @Transactional
    public OriginCheckCreatedResponse create(Long userId, List<MultipartFile> files, String title, List<String> hints) {
        return create(userId, false, files, title, hints);
    }

    /**
     * @param demo 체험 사용자면 true. 올린 파일이 모두 한 체험 세트 안의 파일인지 내용(SHA-256)으로 검사하고(403 DEMO_SAMPLES_ONLY),
     *             사용량 상한을 센 뒤 접수한다. 접수 뒤에는 정식 건과 같이 워커가 agent 로 보낸다
     */
    @Transactional
    public OriginCheckCreatedResponse create(Long userId, boolean demo, List<MultipartFile> files, String title, List<String> hints) {
        if (files == null || files.isEmpty() || files.stream().allMatch(f -> f == null || f.isEmpty())) {
            throw new ApiException(ErrorCode.INVALID_FILE, "빈 파일입니다.");
        }
        if (files.size() > MAX_FILES) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "한 번에 최대 " + MAX_FILES + "개까지 올릴 수 있습니다.");
        }
        long total = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (total > MAX_TOTAL_BYTES) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "한 번에 올리는 파일은 합계 100MB까지입니다.");
        }
        files.forEach(OriginCheckService::validatePdf);
        if (demo) {
            demoGuard.requireOneSet(files); // 저장하기 전에, 사용량을 세기 전에 거른다
        }

        // 1) 원본을 하나씩 저장하고 쪽 수를 센다
        List<Stored> stored = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() == null || f.getOriginalFilename().isBlank() ? "upload.pdf" : f.getOriginalFilename();
            String key;
            try (InputStream in = f.getInputStream()) {
                key = storage.save(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            stored.add(new Stored(name, key, f.getSize(), pdf.pageCount(storage.path(key), name)));
        }

        // 2) 점검에 쓸 PDF: 하나면 그대로, 여러 개면 이어 붙인다
        String bundleKey;
        long bundleSize;
        String bundleName;
        if (stored.size() == 1) {
            bundleKey = stored.getFirst().key();
            bundleSize = stored.getFirst().size();
            bundleName = stored.getFirst().name();
        } else {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("origin-check-bundle", ".pdf");
                pdf.merge(stored.stream().map(s -> storage.path(s.key())).toList(), tmp);
                try (InputStream in = Files.newInputStream(tmp)) {
                    bundleKey = storage.save(in);
                }
                bundleSize = Files.size(tmp);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // 임시 파일은 OS 가 치운다
                    }
                }
            }
            String first = stored.getFirst().name();
            String stem = first.toLowerCase().endsWith(".pdf") ? first.substring(0, first.length() - 4) : first;
            bundleName = stem + " 외 " + (stored.size() - 1) + "건.pdf";
        }

        String t = title == null || title.isBlank() ? bundleName : title.trim();
        OriginCheck draft = new OriginCheck(userId, t.length() > 200 ? t.substring(0, 200) : t,
                cut(bundleName, 255), bundleKey, bundleSize);
        if (demo) {
            draft.markDemo();
        }
        OriginCheck check = checks.save(draft);

        // 3) 파일별 쪽 범위
        int page = 1;
        for (int i = 0; i < stored.size(); i++) {
            Stored s = stored.get(i);
            String hint = hints != null && i < hints.size() && DOC_TYPES.contains(hints.get(i)) ? hints.get(i) : null;
            checkFiles.save(new OriginCheckFile(check.getId(), i, cut(s.name(), 255), s.key(), s.size(), page, page + s.pages() - 1, hint));
            page += s.pages();
        }
        return new OriginCheckCreatedResponse(check.getId(), check.getStatus());
    }

    private record Stored(String name, String key, long size, int pages) {
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Transactional(readOnly = true)
    public PageResponse<OriginCheckSummaryResponse> list(Long userId, CheckStatus status, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "id"));
        var result = status == null ? checks.findByUserId(userId, pageable)
                : checks.findByUserIdAndStatus(userId, status, pageable);
        return PageResponse.of(result, OriginCheckSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public OriginCheckResponse get(Long userId, Long id) {
        OriginCheck c = find(userId, id);
        AgentCheckResult result = c.getResultJson() == null ? null
                : objectMapper.readValue(c.getResultJson(), AgentCheckResult.class);
        return OriginCheckResponse.of(c, result, checkFiles.findByCheckIdOrderBySeqAsc(c.getId()));
    }

    /**
     * 담당자 완료 처리 / 되돌리기. 처리 중(queued·in_progress)인 건은 완료 처리할 수 없다(409).
     * 되돌리기는 상태와 관계없이 된다.
     */
    @Transactional
    public OriginCheckSummaryResponse setDone(Long userId, Long id, boolean done) {
        OriginCheck c = find(userId, id);
        if (done) {
            if (!c.isFinished()) {
                throw new ApiException(ErrorCode.CHECK_NOT_FINISHED);
            }
            c.markDone();
        } else {
            c.reopen();
        }
        return OriginCheckSummaryResponse.from(c);
    }

    /**
     * 실패한 건을 같은 파일로 다시 실행한다. 접수 상태로 돌아가고 워커가 다시 제출한다.
     * 실패(failed)가 아니면 409 CHECK_NOT_FAILED.
     */
    @Transactional
    public OriginCheckCreatedResponse retry(Long userId, Long id) {
        OriginCheck c = find(userId, id);
        if (c.getStatus() != CheckStatus.FAILED) {
            throw new ApiException(ErrorCode.CHECK_NOT_FAILED);
        }
        c.resetForRetry();
        return new OriginCheckCreatedResponse(c.getId(), c.getStatus());
    }

    /**
     * 점검 건 삭제: DB 행(원본 파일 목록은 FK CASCADE), 저장소의 원본·합친 PDF, agent 쪽 파일.
     * AI 가 처리 중(in_progress)이면 409 CHECK_IN_PROGRESS. agent 쪽 파일을 못 지우면 502 AGENT_UNAVAILABLE(아무것도 지우지 않음).
     */
    @Transactional
    public void delete(Long userId, Long id) {
        OriginCheck c = find(userId, id);
        if (c.getStatus() == CheckStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.CHECK_IN_PROGRESS);
        }
        if (c.getAgentFileId() != null) {
            try {
                agent.deleteFile(c.getAgentFileId());
            } catch (AgentException e) {
                log.warn("점검 #{} 삭제 중 agent 파일 {} 삭제 실패: {}", id, c.getAgentFileId(), e.getMessage());
                throw new ApiException(ErrorCode.AGENT_UNAVAILABLE);
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(c.getFileKey());
        checkFiles.findByCheckIdOrderBySeqAsc(id).forEach(f -> keys.add(f.getFileKey()));
        checks.delete(c);
        // 저장소 파일은 DB 삭제가 확정된 뒤에 지운다(롤백되면 파일이 남아 있어야 한다)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("점검 #{} 원본 파일 {} 삭제 실패: {}", id, key, e.getMessage());
                    }
                }
            }
        });
    }

    @Transactional(readOnly = true)
    public StoredFile file(Long userId, Long id) {
        OriginCheck c = find(userId, id);
        return new StoredFile(new FileSystemResource(storage.path(c.getFileKey())), c.getFileName());
    }

    private OriginCheck find(Long userId, Long id) {
        // 남의 점검 건은 존재 여부도 알리지 않는다(404)
        return checks.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(ErrorCode.ORIGIN_CHECK_NOT_FOUND));
    }

    private static void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_FILE, "빈 파일입니다." + (file == null ? "" : " " + file.getOriginalFilename()));
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!PDF_TYPES.contains(type)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(PDF_MAGIC.length);
            if (!Arrays.equals(head, PDF_MAGIC)) {
                throw new ApiException(ErrorCode.INVALID_FILE);
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_FILE);
        }
    }

    public record StoredFile(Resource resource, String fileName) {
    }
}
