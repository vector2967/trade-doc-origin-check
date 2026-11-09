package com.tradedoc.origincheck.agent.upstage;

import com.tradedoc.origincheck.agent.dto.AgentCheckResult;
import com.tradedoc.origincheck.agent.dto.AgentExtracted;
import com.tradedoc.origincheck.agent.dto.AgentFollowUp;
import com.tradedoc.origincheck.agent.dto.AgentJudge;
import com.tradedoc.origincheck.agent.dto.AgentMismatch;
import com.tradedoc.origincheck.agent.dto.Decision;
import com.tradedoc.origincheck.agent.dto.ItemCheck;
import com.tradedoc.origincheck.agent.dto.ExtractNode;
import com.tradedoc.origincheck.agent.dto.FollowUpKind;
import com.tradedoc.origincheck.agent.dto.meta.AgentExtractedMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Upstage responses.retrieve(include=all) 응답 → {@link AgentCheckResult}.
 * 파이썬 dto.py 의 to_check_result · parse_judge · field_meta 를 옮긴 것이다.
 *
 * <p>실측(2026-11-07, Agents API include=all):
 * <ul>
 *   <li>output[].model 이 노드 이름을 담는다. 예: 'Information Extract - 인보이스_추출', 'Instruct - 원산지_판정'</li>
 *   <li>Instruct 노드 text 는 JSON 문자열로 한 번 감싸져 온다</li>
 *   <li>content[].additional_values 도 JSON 문자열이다. 판정 노드는 분기에 쓰인 값을 enum 키로 준다</li>
 *   <li>Studio Instruct 출력에는 인용 표시 '【†1】'가 붙는다</li>
 * </ul>
 */
@Component
public class UpstageResponseParser {

    private static final Logger log = LoggerFactory.getLogger(UpstageResponseParser.class);
    private static final Pattern CITE = Pattern.compile("【[^】]*】");
    /** 인보이스 품목마다 다른 서류·관리대장 값을 짝지어 주는 노드 ('Match - 매칭-1') */
    private static final String MATCH_NODE_PREFIX = "Match";
    /** 판정 노드 5줄 출력의 키 */
    private static final Set<String> JUDGE_KEYS = Set.of("invoice_no", "reason", "mismatch_codes", "missing_documents", "decision");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public UpstageResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** status 가 completed 인 응답을 결과로 바꾼다. */
    public AgentCheckResult toCheckResult(Map<String, Object> resp) {
        String jobId = str(resp.get("id"));
        AgentJudge judge = null;
        AgentFollowUp followUp = null;
        List<ItemCheck> itemChecks = List.of();
        Map<String, List<Object>> extracted = new LinkedHashMap<>();
        Map<String, List<Object>> meta = new LinkedHashMap<>();

        for (Object item : list(resp.get("output"))) {
            if (!(item instanceof Map<?, ?> o)) {
                continue;
            }
            String name = str(o.get("model"));
            List<Map<?, ?>> contents = list(o.get("content")).stream()
                    .filter(Map.class::isInstance).<Map<?, ?>>map(Map.class::cast).toList();
            StringBuilder sb = new StringBuilder();
            List<Map<String, Object>> avs = new ArrayList<>();
            for (Map<?, ?> c : contents) {
                sb.append(unwrap(str(c.get("text"))));
                avs.add(additionalValues(c.get("additional_values")));
            }
            String text = sb.toString();

            Optional<String> ext = ExtractNode.ALL.stream().filter(name::contains).findFirst();
            if (name.contains(ExtractNode.JUDGE)) {
                judge = parseJudge(text);
                Object branch = avs.stream().map(a -> a.get("enum")).filter(e -> e != null).findFirst().orElse(null);
                if (branch != null && Decision.isKnown(branch.toString())) {
                    judge = judge.withDecision(Decision.fromLabel(branch.toString())); // 분기에 실제로 쓰인 결정값이 우선
                }
            } else if (name.startsWith(MATCH_NODE_PREFIX)) {
                try {
                    itemChecks = ItemCheck.fromMatch(parseObject(text, name));
                } catch (RuntimeException e) { // 품목별 비교는 보조 정보라 못 만들어도 결과 전체를 실패시키지 않는다
                    log.warn("Match 노드 출력으로 품목별 비교를 만들지 못함: {}", e.getMessage());
                }
            } else if (ext.isPresent()) {
                extracted.computeIfAbsent(ext.get(), k -> new ArrayList<>()).add(parseObject(text, ext.get()));
                Map<String, Object> av = avs.stream().filter(a -> !a.isEmpty()).findFirst().orElse(Map.of());
                meta.computeIfAbsent(ext.get(), k -> new ArrayList<>()).add(fieldMeta(av));
            } else {
                Optional<FollowUpKind> kind = FollowUpKind.findIn(name);
                if (kind.isPresent()) {
                    followUp = new AgentFollowUp(kind.get(), stripCites(text));
                }
            }
        }

        if (judge != null) {
            judge = judge.withItemChecks(itemChecks);
        }
        List<AgentMismatch> mismatches = judge == null ? List.of()
                : judge.mismatchCodes().stream().map(AgentMismatch::of).toList();
        String error = errorMessage(resp.get("error"));
        if (judge == null && followUp == null && error == null) {
            error = "판정 노드 출력을 찾지 못함";
        }
        return new AgentCheckResult(jobId, judge, mismatches, followUp,
                mapper.convertValue(extracted, AgentExtracted.class),
                mapper.convertValue(meta, AgentExtractedMeta.class),
                error);
    }

    /** 판정 노드 텍스트(5줄 'key: value') 또는 JSON 을 받는다. decision 이 이상하면 '검토 필요'. */
    public AgentJudge parseJudge(String raw) {
        String text = stripCites(raw);
        Map<String, Object> d = new LinkedHashMap<>();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        boolean parsed = false;
        if (start >= 0 && end > start) { // 코드 블록(```json)으로 감싸 와도 첫 { ~ 마지막 } 만 읽는다
            try {
                d.putAll(mapper.readValue(text.substring(start, end + 1), MAP));
                parsed = true;
            } catch (JacksonException ignored) {
                // 아래 'key: value' 형식으로 읽는다
            }
        }
        if (!parsed) {
            // 아는 키(invoice_no 등)로 시작하는 줄만 새 값이다. 그 밖의 줄은 앞 값에 이어 붙인다
            // → reason 뒤에 품목별 근거('PSR 품명: … → 충족')를 줄을 바꿔 써도 reason 에 남는다
            String current = null;
            for (String line : text.split("\\R")) {
                int i = line.indexOf(':');
                String key = i > 0 ? line.substring(0, i).trim().toLowerCase() : null;
                if (key != null && JUDGE_KEYS.contains(key)) {
                    current = key;
                    d.put(key, line.substring(i + 1).trim());
                } else if (current != null && !line.isBlank()) {
                    String prev = d.get(current).toString();
                    d.put(current, prev.isEmpty() ? line.trim() : prev + "\n" + line.trim());
                }
            }
        }
        String invoiceNo = d.get("invoice_no") == null ? null : d.get("invoice_no").toString().trim();
        return new AgentJudge(
                invoiceNo == null || invoiceNo.isEmpty() ? null : invoiceNo,
                Decision.fromLabel(str(d.get("decision"))),
                str(d.get("reason")),
                stringList(d.get("mismatch_codes")),
                stringList(d.get("missing_documents")));
    }

    /** Extract 노드 additional_values → 필드별 신뢰도·좌표. 필드가 아닌 항목(step_run_id 등)은 버린다. */
    public Map<String, Object> fieldMeta(Map<String, Object> av) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (av != null) {
            av.forEach((k, v) -> {
                Object m = oneMeta(v);
                if (m != null) {
                    out.put(k, m);
                }
            });
        }
        return out;
    }

    private Object oneMeta(Object v) {
        if (v instanceof Map<?, ?> m && m.containsKey("_value")) {
            Object boxes = m.get("word_coordinates");
            if (isEmpty(boxes)) {
                boxes = isEmpty(m.get("coordinates")) ? List.of() : List.of(m.get("coordinates"));
            }
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("confidence", m.get("confidence"));
            fm.put("score", m.get("confidence_score"));
            fm.put("page", m.get("page"));
            fm.put("boxes", boxes);
            return fm;
        }
        if (v instanceof List<?> rows && !rows.isEmpty() && rows.stream().allMatch(Map.class::isInstance)) {
            // items 같은 표: 행마다 같은 구조
            List<Object> out = new ArrayList<>();
            for (Object row : rows) {
                Map<String, Object> r = new LinkedHashMap<>();
                ((Map<?, ?>) row).forEach((k, x) -> {
                    Object m = oneMeta(x);
                    if (m != null) {
                        r.put(k.toString(), m);
                    }
                });
                out.add(r);
            }
            return out;
        }
        return null;
    }

    public static String stripCites(String text) {
        return text == null ? "" : CITE.matcher(text).replaceAll("");
    }

    /** '"invoice_no: …\n…"' 처럼 JSON 문자열로 감싼 텍스트를 푼다. 객체 JSON 이나 일반 텍스트는 그대로. */
    private String unwrap(String text) {
        if (text.startsWith("\"")) {
            try {
                Object v = mapper.readValue(text, Object.class);
                return v instanceof String s ? s : text;
            } catch (JacksonException ignored) {
                // 그대로
            }
        }
        return text;
    }

    private Map<String, Object> additionalValues(Object v) {
        if (v instanceof String s) {
            try {
                return mapper.readValue(s, MAP);
            } catch (JacksonException e) {
                return Map.of();
            }
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(k.toString(), x));
            return out;
        }
        return Map.of();
    }

    private Map<String, Object> parseObject(String text, String node) {
        try {
            return mapper.readValue(text, MAP);
        } catch (JacksonException e) {
            log.warn("{} 출력이 JSON 이 아니라 빈 값으로 둡니다: {}", node, text.length() > 200 ? text.substring(0, 200) : text);
            return Map.of();
        }
    }

    private List<String> stringList(Object v) {
        List<?> items;
        if (v == null) {
            items = List.of();
        } else if (v instanceof List<?> l) {
            items = l;
        } else {
            String s = v.toString().trim();
            List<?> parsed;
            try {
                parsed = mapper.readValue(s, LIST);
            } catch (JacksonException e) {
                String inner = s.replaceAll("^\\[|]$", "");
                parsed = inner.isBlank() ? List.of() : List.of(inner.split(","));
            }
            items = parsed;
        }
        List<String> out = new ArrayList<>();
        for (Object o : items) {
            String s = o == null ? "" : o.toString().trim().replaceAll("^['\"]|['\"]$", "");
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String errorMessage(Object error) {
        if (error == null) {
            return null;
        }
        if (error instanceof Map<?, ?> m && m.get("message") != null) {
            return m.get("message").toString();
        }
        return error.toString();
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof List<?> l && l.isEmpty());
    }

    private static List<?> list(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
