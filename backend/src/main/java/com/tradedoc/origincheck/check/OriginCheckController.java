package com.tradedoc.origincheck.check;

import com.tradedoc.origincheck.auth.AuthUser;
import com.tradedoc.origincheck.check.dto.OriginCheckCreatedResponse;
import com.tradedoc.origincheck.check.dto.OriginCheckResponse;
import com.tradedoc.origincheck.check.dto.OriginCheckSummaryResponse;
import com.tradedoc.origincheck.common.PageResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/origin-checks")
public class OriginCheckController {

    private final OriginCheckService service;

    public OriginCheckController(OriginCheckService service) {
        this.service = service;
    }

    /**
     * 한 선적 건의 서류 PDF 를 접수한다. file 파트를 여러 개(최대 20개, 합계 100MB) 보낼 수 있고, 하나로 합친 PDF 1개도 된다.
     * hint 는 file 과 같은 순서의 서류 종류 짐작(선택). 처리는 비동기라 바로 202 와 id 를 준다.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OriginCheckCreatedResponse> create(@AuthenticationPrincipal AuthUser user,
                                                             @RequestPart("file") List<MultipartFile> files,
                                                             @RequestParam(value = "title", required = false) String title,
                                                             @RequestParam(value = "hint", required = false) List<String> hints) {
        OriginCheckCreatedResponse created = service.create(user.id(), user.demo(), files, title, hints);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.accepted().location(location).body(created);
    }

    /** 내 점검 목록(최신순). status 로 거를 수 있다. */
    @GetMapping
    public PageResponse<OriginCheckSummaryResponse> list(@AuthenticationPrincipal AuthUser user,
                                                         @RequestParam(value = "status", required = false) String status,
                                                         @RequestParam(value = "page", defaultValue = "0") int page,
                                                         @RequestParam(value = "size", defaultValue = "20") int size) {
        return service.list(user.id(), CheckStatus.fromValue(status), page, size);
    }

    /** 점검 상세. 완료 전에는 status 만 의미가 있다 → 프론트는 completed/failed 가 될 때까지 몇 초마다 조회한다. */
    @GetMapping("/{id}")
    public OriginCheckResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return service.get(user.id(), id);
    }

    /** 담당자 완료 처리. 진행 중 목록에서 빠진다. 이미 완료면 그대로(처음 시각 유지). 처리 중인 건은 409 */
    @PutMapping("/{id}/done")
    public OriginCheckSummaryResponse markDone(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return service.setDone(user.id(), id, true);
    }

    /** 완료 처리 되돌리기 (진행 중으로) */
    @DeleteMapping("/{id}/done")
    public OriginCheckSummaryResponse reopen(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return service.setDone(user.id(), id, false);
    }

    /** 실패한 건 다시 실행(같은 파일). 접수 상태로 돌아가 202. 실패가 아니면 409 CHECK_NOT_FAILED */
    @PostMapping("/{id}/retry")
    public ResponseEntity<OriginCheckCreatedResponse> retry(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return ResponseEntity.accepted().body(service.retry(user.id(), id));
    }

    /** 점검 건 삭제(원본 파일 포함). AI 처리 중이면 409 CHECK_IN_PROGRESS */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        service.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** 원본 PDF (화면의 PDF 뷰어·하이라이트용) */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        OriginCheckService.StoredFile f = service.file(user.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(f.resource());
    }
}
