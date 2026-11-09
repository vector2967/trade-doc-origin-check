package com.tradedoc.origincheck.agent.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PlainTextTest {

    /** 프론트 통과 기준: 코드값 · JSON 키 · true/false · [] 가 남지 않는다 */
    private static final Pattern LEAK = Pattern.compile(
            "[A-Z]{2,}_[A-Z_]+|issued_retroactively|mismatch_codes|missing_documents|invoice_no|\\btrue\\b|\\bfalse\\b|\\[\\]");

    @Test
    void 운영_문장_7건에서_코드와_키가_사라진다() throws Exception {
        Map<String, Map<String, String>> prod;
        try (InputStream in = getClass().getResourceAsStream("/plain_text_prod.json")) {
            prod = JsonMapper.builder().build().readValue(in, new TypeReference<>() {});
        }
        prod.forEach((id, row) -> {
            for (String key : new String[]{"reason", "follow_up"}) {
                String out = PlainText.humanize(row.get(key));
                if (out != null) {
                    assertThat(LEAK.matcher(out).find()).as("#%s %s: %s", id, key, out).isFalse();
                }
            }
        });
    }

    @Test
    void 운영_판정_문장() {
        // #5
        assertThat(PlainText.humanize("…허용 기준은 \"CTC, RVC 40%\"로 기재되어 있어 CO_CRITERION_NOT_PSR 검토가 필요합니다."))
                .isEqualTo("…허용 기준은 \"CTC, RVC 40%\"로 기재되어 있어 ‘원산지증명서 결정기준이 협정 PSR에 없음’ 검토가 필요합니다.");
        // #7
        assertThat(PlainText.humanize("…뒤 4자리가 달라 CO_HS_MINOR_DIFF에 해당하고, 원산지증명서 발급일"))
                .isEqualTo("…뒤 4자리가 달라 ‘HS 10자리 중 뒤 4자리 차이’에 해당하고, 원산지증명서 발급일");
        // #8
        assertThat(PlainText.humanize("…SM-2609-0044이어서 CO_INVOICE_NO_MISMATCH에 해당합니다."))
                .isEqualTo("…SM-2609-0044이어서 ‘원산지증명서 송장번호 ≠ 인보이스’에 해당합니다.");
        // #12
        assertThat(PlainText.humanize("B/L 적재일은 2026-11-05로 9일 늦으며 issued_retroactively는 false입니다."))
                .isEqualTo("B/L 적재일은 2026-11-05로 9일 늦으며 소급발급 표시가 없습니다.");
        // #2: '1단계 코드' 같은 군말도 뺀다
        assertThat(PlainText.humanize("미충족(N)으로 표시되어 있어, 1단계 코드 SUP_CRITERION_NOT_MET에 해당합니다."))
                .isEqualTo("미충족(N)으로 표시되어 있어, ‘원산지확인서 결정기준 미충족(N)’에 해당합니다.");
    }

    @Test
    void 운영_후속_문서() {
        // #8 보완 요청 초안의 참고 목록: 키는 우리말로, 빈 '빠진 서류' 줄은 지운다
        assertThat(PlainText.humanize("""
                #### 참고
                - 판정 결과 invoice_no는 **SM-2609-0044**입니다.
                - mismatch_codes는 **["CO_INVOICE_NO_MISMATCH"]**입니다.
                - missing_documents는 **[]**입니다."""))
                .isEqualTo("""
                        #### 참고
                        - 판정 결과 송장번호는 **SM-2609-0044**입니다.
                        - 불일치 항목은 **원산지증명서 송장번호 ≠ 인보이스**입니다.
                        """);
        // #4 검토 메모: 문장 안이면 줄은 두고 '없습니다'로
        assertThat(PlainText.humanize("**mismatch_codes**는 **[\"CO_CRITERION_NOT_PSR\"]**이고, **missing_documents**는 **[]**입니다."))
                .isEqualTo("**불일치 항목**은 **원산지증명서 결정기준이 협정 PSR에 없음**이고, **빠진 서류**는 **없습니다**.");
        // #12 검토 메모
        assertThat(PlainText.humanize("9일 늦고 issued_retroactively는 false**이기 때문입니다. 현재 불일치 코드는 **CO_ISSUE_DATE_LATE**입니다."))
                .isEqualTo("9일 늦고 소급발급 표시가 없기** 때문입니다. 현재 불일치 항목은 **원산지증명서 발급일이 적재일보다 3일 넘게 늦음**입니다.");
    }

    @Test
    void 대괄호_머리말과_백틱() {
        assertThat(PlainText.humanize("안내드리겠습니다.\n\n[사내 메모] 공급사에 확인")).isEqualTo("안내드리겠습니다.\n\n[사내 메모] 공급사에 확인");
        assertThat(PlainText.humanize("`SUP_EXPIRED` 사유로 보완")).isEqualTo("‘원산지확인서 포괄확인기간 만료’ 사유로 보완");
        assertThat(PlainText.humanize("**9일 늦고** `issued_retroactively`가 **false**이기 때문입니다."))
                .isEqualTo("**9일 늦고** 소급발급 표시가 없기 때문입니다.");
        assertThat(PlainText.humanize("- **불일치 항목**: X\n- **빠진 서류**: 없음 (`[]`)")).isEqualTo("- **불일치 항목**: X\n");
        assertThat(PlainText.humanize("  - **missing_documents:** 없음\n- 끝")).isEqualTo("- 끝");
        assertThat(PlainText.humanize("- **reason**: 다릅니다.")).isEqualTo("- **판정 이유**: 다릅니다.");
        assertThat(PlainText.humanize("Please reissue it for the following reason: the decision was fulfilled."))
                .isEqualTo("Please reissue it for the following reason: the decision was fulfilled.");
        assertThat(PlainText.humanize("mismatch 코드는 CO_HS_MINOR_DIFF입니다. 사유 코드는 SUP_EXPIRED입니다."))
                .isEqualTo("불일치 항목은 ‘HS 10자리 중 뒤 4자리 차이’입니다. 사유는 ‘원산지확인서 포괄확인기간 만료’입니다.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "fulfilled는 Y입니다|충족 여부는 Y입니다",
            "origin_criterion이 다릅니다|결정기준이 다릅니다",
            "laden_on_board_date와 issue_date를 비교|B/L 적재일과 발급일을 비교",
            "hs_code로 비교|HS 코드로 비교",
            "빠진 서류: [\"원산지확인서\", \"B/L\"]|빠진 서류: 원산지확인서, B/L",
            "UNKNOWN_CODE_X 는 그대로|UNKNOWN_CODE_X 는 그대로",
    })
    void 필드명과_조사(String in, String out) {
        assertThat(PlainText.humanize(in)).isEqualTo(out);
    }

    @Test
    void 바꿀_게_없으면_그대로() {
        String s = "원산지증명서 송장번호 SM-2609-0815가 인보이스 SM-2609-0044와 다릅니다.";
        assertThat(PlainText.humanize(s)).isEqualTo(s);
        assertThat(PlainText.humanize(null)).isNull();
        assertThat(PlainText.humanize("")).isEmpty();
    }
}
