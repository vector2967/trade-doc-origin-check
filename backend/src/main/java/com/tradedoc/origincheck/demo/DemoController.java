package com.tradedoc.origincheck.demo;

import com.tradedoc.origincheck.common.ApiException;
import com.tradedoc.origincheck.common.ErrorCode;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/** 체험용 서류(인증 없음). 체험이 꺼져 있으면 404 */
@RestController
@RequestMapping("/api/v1/demo/samples")
public class DemoController {

    private final DemoSamples samples;
    private final DemoProperties props;

    public DemoController(DemoSamples samples, DemoProperties props) {
        this.samples = samples;
        this.props = props;
    }

    @GetMapping
    public Map<String, Collection<DemoSamples.SampleSet>> list() {
        requireEnabled();
        return Map.of("sets", samples.sets());
    }

    @GetMapping("/{setId}/{fileName}")
    public ResponseEntity<Resource> file(@PathVariable String setId, @PathVariable String fileName) {
        requireEnabled();
        // macOS 등에서 자모가 풀린(NFD) 이름으로 와도 manifest 의 이름(NFC)과 맞춘다
        String name = Normalizer.normalize(fileName, Normalizer.Form.NFC);
        Resource pdf = samples.file(setId, name).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }

    private void requireEnabled() {
        if (!props.enabled()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }
}
