package com.tradedoc.origincheck.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 품목별 비교 한 줄. 백엔드가 워크플로 Match 노드 출력(인보이스 품목마다 다른 서류·관리대장 값이 짝지어진 것)에서 만든다.
 * LLM 문장을 읽지 않으므로 형식이 늘 같다.
 *
 * @param kind      PSR | HS
 * @param item      품명(인보이스)
 * @param hs        인보이스 HS(숫자만). 없으면 null
 * @param docValue  PSR: 원산지증명서 결정기준 / HS: 인보이스 HS
 * @param ruleValue PSR: 품목 원산지 관리대장 허용 기준(allowed_criteria) / HS: 수출신고필증 HS
 * @param ruleText  PSR 일 때 관리대장의 협정 PSR 원문(akfta_psr). 없으면 null
 * @param result    PSR: 충족 · 불충족 · 확인불가 · 비교 비대상 / HS: 같음 · 6단위 같음 · 다름 · 확인불가
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ItemCheck(String kind, String item, String hs, String docValue, String ruleValue, String ruleText,
                        String result) {

    public static final String PSR = "PSR";
    public static final String HS = "HS";

    private static final String CO = "원산지증명서_추출__";
    private static final String ED = "수출신고필증_추출__";
    private static final String LEDGER_SUFFIX = "_품목원산지관리대장__";

    /**
     * @param match Match 노드 출력(인보이스 객체 + items 마다 '노드__필드' 키)
     */
    public static List<ItemCheck> fromMatch(Map<String, Object> match) {
        List<ItemCheck> out = new ArrayList<>();
        if (match == null || !(match.get("items") instanceof List<?> items)) {
            return out;
        }
        boolean koreaEu = !text(match.get("origin_declaration")).isEmpty(); // 한-EU 원산지신고 건은 PSR 비교를 하지 않는다
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }
            String item = text(row.get("description"));
            String invoiceHs = digits(row.get("hs_code"));
            if (row.containsKey(CO + "origin_criterion")) {
                out.add(psr(row, item, invoiceHs, koreaEu));
            }
            if (row.containsKey(ED + "hs_code")) {
                out.add(hs(item, invoiceHs, digits(row.get(ED + "hs_code"))));
            }
        }
        return out;
    }

    private static ItemCheck psr(Map<?, ?> row, String item, String invoiceHs, boolean koreaEu) {
        String criterion = unquote(text(row.get(CO + "origin_criterion")));
        String allowed = ledger(row, "allowed_criteria");
        String psrText = ledger(row, "akfta_psr");
        String result;
        if (koreaEu || allowed.isEmpty()) {
            result = "비교 비대상"; // 관리대장에 짝이 없는 품목 · 한-EU 건
        } else if (criterion.isEmpty()) {
            result = "확인불가";
        } else {
            String c = norm(criterion);
            result = Arrays.stream(allowed.split(",")).map(ItemCheck::norm).anyMatch(c::equals) ? "충족" : "불충족";
        }
        return new ItemCheck(PSR, item, blankToNull(invoiceHs), blankToNull(criterion), blankToNull(allowed),
                blankToNull(psrText), result);
    }

    private static ItemCheck hs(String item, String invoiceHs, String declHs) {
        String result;
        if (invoiceHs.isEmpty() || declHs.isEmpty()) {
            result = "확인불가";
        } else if (invoiceHs.equals(declHs)) {
            result = "같음";
        } else if (invoiceHs.length() >= 6 && declHs.length() >= 6 && invoiceHs.substring(0, 6).equals(declHs.substring(0, 6))) {
            result = "6단위 같음";
        } else {
            result = "다름";
        }
        return new ItemCheck(HS, item, blankToNull(invoiceHs), blankToNull(invoiceHs), blankToNull(declHs), null, result);
    }

    /** 관리대장 키는 '<회사>_품목원산지관리대장__<필드>' 라서 접두어를 모른 채 찾는다 */
    private static String ledger(Map<?, ?> row, String field) {
        for (Map.Entry<?, ?> e : row.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.endsWith(LEDGER_SUFFIX + field)) {
                return text(e.getValue());
            }
        }
        return "";
    }

    private static String norm(String s) {
        return unquote(s).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** 추출 값이 '"WO"' 처럼 따옴표째 오기도 한다 */
    private static String unquote(String s) {
        return s.trim().replaceAll("^[\"'“”‘’\\[(]+|[\"'“”‘’\\])]+$", "").trim();
    }

    private static String digits(Object v) {
        return text(v).replaceAll("\\D", "");
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
