package com.tradedoc.origincheck.agent.upstage;

import com.tradedoc.origincheck.agent.dto.AgentCheckResult;
import com.tradedoc.origincheck.agent.dto.AgentJudge;
import com.tradedoc.origincheck.agent.dto.Decision;
import com.tradedoc.origincheck.agent.dto.FollowUpKind;
import com.tradedoc.origincheck.agent.dto.meta.FieldMeta;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 파이썬 dto.py 의 자기 점검(__main__)을 옮긴 테스트. */
class UpstageResponseParserTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final UpstageResponseParser parser = new UpstageResponseParser(mapper);

    private static Map<String, Object> output(String model, String text) {
        return Map.of("model", model, "content", List.of(Map.of("type", "output_text", "text", text)));
    }

    @Test
    void 완료_응답을_결과로_바꾼다() {
        Map<String, Object> resp = Map.of("id", "job_demo", "status", "completed", "output", List.of(
                output("step_9_원산지_판정", """
                        invoice_no: SM-2609-0044
                        reason: 원산지증명서 송장번호 SM-2609-0041이 인보이스 SM-2609-0044와 다름
                        mismatch_codes: ["CO_INVOICE_NO_MISMATCH"]
                        missing_documents: []
                        decision: 보완 요청"""),
                output("step_10_보완_요청_초안", "제목: 원산지증명서 재발급 요청 …【†1】"),
                output("step_3_인보이스_추출",
                        "{\"invoice_no\": \"SM-2609-0044\", \"items\": [{\"description\": \"FROZEN MACKEREL\", \"hs_code\": \"0303540000\", \"quantity\": 18000}]}")));

        AgentCheckResult r = parser.toCheckResult(resp);

        assertThat(r.jobId()).isEqualTo("job_demo");
        assertThat(r.extracted().invoices().get(0).items().get(0).hsCode()).isEqualTo("0303540000");
        assertThat(r.extracted().invoices().get(0).items().get(0).quantity()).isEqualTo(18000);
        assertThat(r.extracted().invoices().get(0).shipper()).isEmpty(); // 없는 값은 ""
        assertThat(r.judge().decision()).isEqualTo(Decision.SUPPLEMENT_REQUEST);
        assertThat(r.judge().mismatchCodes()).containsExactly("CO_INVOICE_NO_MISMATCH");
        assertThat(r.mismatches().get(0).stage()).isEqualTo(2);
        assertThat(r.mismatches().get(0).label()).isEqualTo("원산지증명서 송장번호 ≠ 인보이스");
        assertThat(r.followUp().kind()).isEqualTo(FollowUpKind.SUPPLEMENT_REQUEST_DRAFT);
        assertThat(r.followUp().text()).doesNotContain("【");
        assertThat(r.error()).isNull();
    }

    @Test
    void JSON_문자열로_감싼_판정과_분기_enum을_읽는다() throws Exception {
        String wrapped = mapper.writeValueAsString("invoice_no: A\nreason: r\nmismatch_codes: []\nmissing_documents: [\"B/L\"]\ndecision: 특혜 적용");
        Map<String, Object> resp = Map.of("id", "j", "status", "completed", "output", List.of(Map.of(
                "model", "Instruct - 원산지_판정",
                "content", List.of(Map.of("text", wrapped, "additional_values", "{\"enum\": \"검토 필요\"}")))));

        AgentJudge judge = parser.toCheckResult(resp).judge();

        assertThat(judge.invoiceNo()).isEqualTo("A");
        assertThat(judge.missingDocuments()).containsExactly("B/L");
        assertThat(judge.decision()).isEqualTo(Decision.REVIEW_REQUIRED); // 분기에 쓰인 값이 우선
    }

    @Test
    void 판정_JSON과_이상한_decision() {
        assertThat(parser.parseJudge("```json\n{\"invoice_no\":\"A\",\"reason\":\"r\",\"mismatch_codes\":[\"CO_CRITERION_NOT_PSR\"],\"missing_documents\":[],\"decision\":\"검토 필요\"}\n```").decision())
                .isEqualTo(Decision.REVIEW_REQUIRED);
        assertThat(parser.parseJudge("{\"invoice_no\":\"A\",\"decision\":\"이상값\",\"reason\":\"\",\"mismatch_codes\":[],\"missing_documents\":[\"B/L\"]}").decision())
                .isEqualTo(Decision.REVIEW_REQUIRED);
        assertThat(parser.parseJudge("invoice_no: \nmismatch_codes: [CO_QTY_MISMATCH, 'QTY_MISMATCH']\ndecision: 미적용"))
                .satisfies(j -> {
                    assertThat(j.invoiceNo()).isNull();
                    assertThat(j.mismatchCodes()).containsExactly("CO_QTY_MISMATCH", "QTY_MISMATCH");
                    assertThat(j.decision()).isEqualTo(Decision.NOT_APPLICABLE);
                });
    }

    @Test
    void reason_뒤에_줄을_바꿔_쓴_품목별_근거도_reason에_남는다() {
        // 설정 7: reason 끝에 품목마다 'PSR 품명: … → 충족' · 'HS 품명: … → 같음' 을 한 줄씩 적는다
        AgentJudge j = parser.parseJudge("""
                invoice_no: SA-2609-0014
                reason: 원산지증명서와 인보이스가 일치합니다.
                PSR FROZEN MACKEREL: CTH / CC, CTH, RVC 40% → 충족

                HS FROZEN MACKEREL: 0303540000 / 0303540000 → 같음
                mismatch_codes: []
                missing_documents: []
                decision: 특혜 적용""");

        assertThat(j.invoiceNo()).isEqualTo("SA-2609-0014");
        assertThat(j.reason()).isEqualTo("""
                원산지증명서와 인보이스가 일치합니다.
                PSR FROZEN MACKEREL: CTH / CC, CTH, RVC 40% → 충족
                HS FROZEN MACKEREL: 0303540000 / 0303540000 → 같음""");
        assertThat(j.mismatchCodes()).isEmpty();
        assertThat(j.decision()).isEqualTo(Decision.PREFERENTIAL);
    }

    @Test
    void 필드_메타() {
        List<Map<String, Double>> box = List.of(Map.of("x", 0.1, "y", 0.1), Map.of("x", 0.2, "y", 0.1),
                Map.of("x", 0.2, "y", 0.12), Map.of("x", 0.1, "y", 0.12));
        Map<String, Object> av = Map.of(
                "step_run_id", "x",
                "invoice_no", Map.of("_value", "A", "confidence", "high", "confidence_score", 0.99, "page", 1,
                        "coordinates", List.of(), "word_coordinates", List.of(box)),
                "items", List.of(Map.of("hs_code", Map.of("_value", "1", "confidence", "low", "page", -1))));

        Map<String, Object> m = parser.fieldMeta(av);

        assertThat(m).containsOnlyKeys("invoice_no", "items");
        Map<String, Object> resp = Map.of("id", "j", "status", "completed", "output", List.of(Map.of(
                "model", "Information Extract - 인보이스_추출",
                "content", List.of(Map.of("text", "{\"invoice_no\":\"A\"}", "additional_values", av)))));
        var meta = parser.toCheckResult(resp).extractedMeta().invoices().get(0);
        FieldMeta inv = meta.invoiceNo();
        assertThat(inv.confidence()).isEqualTo("high");
        assertThat(inv.score()).isEqualTo(0.99);
        assertThat(inv.page()).isEqualTo(1);
        assertThat(inv.boxes()).hasSize(1);
        assertThat(inv.boxes().get(0).get(2).y()).isEqualTo(0.12);
        FieldMeta hs = meta.items().get(0).hsCode();
        assertThat(hs.isLow()).isTrue();
        assertThat(hs.score()).isNull();
        assertThat(hs.page()).isEqualTo(-1);
        assertThat(hs.boxes()).isEmpty();
        assertThat(meta.shipper()).isNull();
    }

    @Test
    void 판정_노드가_없으면_error() {
        AgentCheckResult r = parser.toCheckResult(Map.of("id", "j", "status", "completed", "output", List.of()));
        assertThat(r.judge()).isNull();
        assertThat(r.error()).isEqualTo("판정 노드 출력을 찾지 못함");
    }
}
