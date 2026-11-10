package com.tradedoc.origincheck.demo;

import com.tradedoc.origincheck.common.ApiException;
import com.tradedoc.origincheck.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 체험 사용자의 업로드 검사: 올린 파일이 모두 <b>한 체험 세트 안의 파일</b>이어야 한다(일부만 와도 된다).
 * 파일 이름이 아니라 내용(SHA-256)으로 본다 → 이름을 바꾼 자기 PDF 로 Upstage 를 쓰는 걸 막는다.
 */
@Component
public class DemoUploadGuard {

    private final DemoSamples samples;

    public DemoUploadGuard(DemoSamples samples) {
        this.samples = samples;
    }

    /** @throws ApiException DEMO_SAMPLES_ONLY(403): 체험용이 아닌 파일이 있거나 두 세트가 섞임 */
    public String requireOneSet(List<MultipartFile> files) {
        Set<String> setIds = new HashSet<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                continue;
            }
            Optional<String> set = samples.setOf(sha256(f));
            if (set.isEmpty()) {
                throw new ApiException(ErrorCode.DEMO_SAMPLES_ONLY);
            }
            setIds.add(set.get());
        }
        if (setIds.size() != 1) {
            throw new ApiException(ErrorCode.DEMO_SAMPLES_ONLY);
        }
        return setIds.iterator().next();
    }

    static String sha256(MultipartFile f) {
        try (InputStream in = new DigestInputStream(f.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
