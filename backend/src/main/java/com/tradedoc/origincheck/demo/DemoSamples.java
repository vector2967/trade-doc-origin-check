package com.tradedoc.origincheck.demo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 체험용 서류 세트. classpath:demo/index.json 의 순서대로 demo/{setId}/manifest.json 을 읽는다.
 * manifest 의 sha256 으로 "체험 사용자가 올린 파일이 어느 세트의 파일인지" 판단한다.
 */
@Component
public class DemoSamples {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SampleFile(String name, long size, String hintType, @JsonIgnore String sha256) {
    }

    public record SampleSet(String id, String name, List<SampleFile> files) {
    }

    private final Map<String, SampleSet> sets = new LinkedHashMap<>();
    /** sha256 → 세트 id */
    private final Map<String, String> setByHash = new HashMap<>();

    public DemoSamples(ObjectMapper mapper) {
        JsonNode index = read(mapper, "demo/index.json");
        for (JsonNode id : index.get("sets")) {
            String setId = id.asString();
            JsonNode m = read(mapper, "demo/" + setId + "/manifest.json");
            List<SampleFile> files = new ArrayList<>();
            for (JsonNode f : m.get("files")) {
                SampleFile sf = new SampleFile(f.get("name").asString(), f.get("size").asLong(),
                        f.get("hint_type").asString(), f.get("sha256").asString());
                files.add(sf);
                String prev = setByHash.put(sf.sha256(), setId);
                if (prev != null && !prev.equals(setId)) {
                    throw new IllegalStateException("같은 파일이 두 체험 세트에 있습니다: " + prev + ", " + setId);
                }
            }
            sets.put(setId, new SampleSet(setId, m.get("name").asString(), List.copyOf(files)));
        }
    }

    public Collection<SampleSet> sets() {
        return sets.values();
    }

    /** 이 해시의 파일이 들어 있는 세트 id */
    public Optional<String> setOf(String sha256) {
        return Optional.ofNullable(setByHash.get(sha256));
    }

    /** 세트의 파일(이름이 manifest 와 정확히 같을 때만). 경로 조작을 막으려고 manifest 에 있는 이름만 연다 */
    public Optional<ClassPathResource> file(String setId, String fileName) {
        SampleSet set = sets.get(setId);
        if (set == null || set.files().stream().noneMatch(f -> f.name().equals(fileName))) {
            return Optional.empty();
        }
        return Optional.of(new ClassPathResource("demo/" + setId + "/" + fileName));
    }

    private static JsonNode read(ObjectMapper mapper, String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("체험용 서류 목록을 읽지 못했습니다: " + path, e);
        }
    }
}
