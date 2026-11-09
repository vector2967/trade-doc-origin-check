package com.tradedoc.origincheck.agent.upstage;

import com.tradedoc.origincheck.agent.dto.AgentJudge;
import com.tradedoc.origincheck.agent.dto.ItemCheck;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 설정 7 실제 응답(2026-11-09 재측정)에서 Match · 판정 노드만 남긴 픽스처로 품목별 비교를 확인한다 */
class ItemChecksTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final UpstageResponseParser parser = new UpstageResponseParser(mapper);

    private AgentJudge judge(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/upstage/item_checks/" + name + ".json")) {
            return parser.toCheckResult(mapper.readValue(in, new TypeReference<Map<String, Object>>() {})).judge();
        }
    }

    @Test
    void set01_PSR_불충족과_HS_같음() throws Exception {
        AgentJudge j = judge("set01_psr_not_met");

        assertThat(j.itemChecks()).extracting(ItemCheck::kind, ItemCheck::item, ItemCheck::docValue, ItemCheck::ruleValue, ItemCheck::result)
                .containsExactly(
                        tuple("PSR", "COTTON T-SHIRTS", "CTH", "CTC, RVC 40%", "불충족"),
                        tuple("HS", "COTTON T-SHIRTS", "6109100000", "6109100000", "같음"));
        assertThat(j.itemChecks().getFirst().ruleText()).startsWith("CC, provided");
        assertThat(j.itemChecks().getFirst().hs()).isEqualTo("6109100000");
        // reason 끝의 'PSR 품명: …' · 'HS 품명: …' 은 item_checks 로 옮겼으므로 뗀다
        assertThat(j.reason()).doesNotContain("PSR 품명", "HS 품명").endsWith("검토가 필요합니다.");
    }

    @Test
    void set03_HS_10단위만_다르면_6단위_같음() throws Exception {
        AgentJudge j = judge("set03_hs_6digit");

        assertThat(j.itemChecks()).filteredOn(c -> c.kind().equals("HS"))
                .extracting(ItemCheck::item, ItemCheck::docValue, ItemCheck::ruleValue, ItemCheck::result)
                .containsExactly(
                        tuple("LED MONITOR 24 INCH", "8528521000", "8528521000", "같음"),
                        tuple("LED LIGHT BULBS", "8539521000", "8539529000", "6단위 같음"));
        assertThat(j.reason()).endsWith("0일입니다."); // '…0일이며 PSR …' 에서 끊긴 문장을 맺는다
    }

    @Test
    void set08_따옴표째_추출된_결정기준도_비교한다() throws Exception {
        assertThat(judge("set08_quoted_wo").itemChecks()).filteredOn(c -> c.kind().equals("PSR"))
                .extracting(ItemCheck::docValue, ItemCheck::result)
                .containsExactly(tuple("WO", "충족"));
    }

    @Test
    void set13_품목_2개() throws Exception {
        AgentJudge j = judge("set13_two_items");

        assertThat(j.itemChecks()).extracting(ItemCheck::kind, ItemCheck::item, ItemCheck::result).containsExactly(
                tuple("PSR", "LED LIGHT BULBS", "충족"), tuple("HS", "LED LIGHT BULBS", "같음"),
                tuple("PSR", "PLASTIC FOOD CONTAINERS", "충족"), tuple("HS", "PLASTIC FOOD CONTAINERS", "같음"));
        assertThat(j.reason()).doesNotContain("HS LED", "HS PLASTIC").endsWith("충족합니다.");
    }

    @Test
    void 관리대장_짝이_없거나_결정기준이_비면() {
        Map<String, Object> match = Map.of("items", List.of(
                Map.of("description", "A", "hs_code", "1234.56-7890", "원산지증명서_추출__origin_criterion", "CTH"),
                Map.of("description", "B", "hs_code", "1234567890", "원산지증명서_추출__origin_criterion", "",
                        "X_품목원산지관리대장__allowed_criteria", "CTH"),
                Map.of("description", "C", "hs_code", "1234567890", "수출신고필증_추출__hs_code", "")));

        assertThat(ItemCheck.fromMatch(match)).extracting(ItemCheck::kind, ItemCheck::item, ItemCheck::hs, ItemCheck::result)
                .containsExactly(
                        tuple("PSR", "A", "1234567890", "비교 비대상"),
                        tuple("PSR", "B", "1234567890", "확인불가"),
                        tuple("HS", "C", "1234567890", "확인불가"));
        assertThat(ItemCheck.fromMatch(Map.of("origin_declaration", "KR", "items", List.of(
                Map.of("description", "D", "원산지증명서_추출__origin_criterion", "CTH", "X_품목원산지관리대장__allowed_criteria", "CTH")))))
                .extracting(ItemCheck::result).containsExactly("비교 비대상"); // 한-EU 건
        assertThat(ItemCheck.fromMatch(null)).isEmpty();
        assertThat(ItemCheck.fromMatch(Map.of())).isEmpty();
    }

    @Test
    void Match_노드가_없으면_빈_배열이고_reason은_그대로() {
        AgentJudge j = parser.toCheckResult(Map.of("id", "r", "status", "completed", "output", List.of(
                Map.of("model", "Instruct - 원산지_판정", "content", List.of(Map.of("text",
                        "invoice_no: A\nreason: 일치합니다. HS X: 1 / 1 → 같음\nmismatch_codes: []\nmissing_documents: []\ndecision: 특혜 적용")))))).judge();

        assertThat(j.itemChecks()).isEmpty();
        assertThat(j.reason()).isEqualTo("일치합니다. HS X: 1 / 1 → 같음");
    }
}
