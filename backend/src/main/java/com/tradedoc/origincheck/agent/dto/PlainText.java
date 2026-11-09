package com.tradedoc.origincheck.agent.dto;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 워크플로가 쓴 문장(judge.reason · follow_up.text)에서 내부 표기를 사람 말로 바꾼다.
 * 프롬프트가 금지해도 LLM 이 코드값·JSON 키를 쓰는 경우가 있어서 화면에 나가기 전에 거르는 안전망이다.
 * <ul>
 *   <li>불일치 코드(CO_INVOICE_NO_MISMATCH 등) → 코드표 라벨 ('원산지증명서 송장번호 ≠ 인보이스')</li>
 *   <li>JSON 키(mismatch_codes · issued_retroactively 등) → 우리말 ('불일치 항목' · '소급발급 표시')</li>
 *   <li>[] · ["…"] · true · false → '없음' · 목록 · '있음' · '없음'</li>
 *   <li>'빠진 서류는 없음입니다' 만 있는 목록 줄은 지운다</li>
 * </ul>
 * 바꾼 말 뒤의 조사(은/는 · 이/가 · 을/를 · 과/와 · 으로/로)는 받침에 맞춘다. DB 에는 원문을 두고 응답할 때만 바꾼다.
 */
public final class PlainText {

    private static final Map<String, String> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("mismatch_codes", "불일치 항목");
        FIELDS.put("missing_documents", "빠진 서류");
        FIELDS.put("issued_retroactively", "소급발급 표시");
        FIELDS.put("invoice_no", "송장번호");
        FIELDS.put("reason", "판정 이유");
        FIELDS.put("decision", "판정");
        FIELDS.put("invoice_date", "송장 날짜");
        FIELDS.put("origin_criterion", "결정기준");
        FIELDS.put("allowed_criteria", "허용 기준");
        FIELDS.put("akfta_psr", "협정 PSR");
        FIELDS.put("fulfilled", "충족 여부");
        FIELDS.put("laden_on_board_date", "B/L 적재일");
        FIELDS.put("departure_date", "출항일");
        FIELDS.put("issue_date", "발급일");
        FIELDS.put("declaration_date", "수출신고일");
        FIELDS.put("acceptance_date", "신고수리일");
        FIELDS.put("period_from", "포괄확인기간 시작일");
        FIELDS.put("period_to", "포괄확인기간 종료일");
        FIELDS.put("hs_code", "HS 코드");
        FIELDS.put("origin_country", "원산지");
        FIELDS.put("destination_country", "목적국");
        FIELDS.put("importing_country", "수입국");
        FIELDS.put("exporter_country", "생산국");
        FIELDS.put("port_of_discharge", "양하항");
        FIELDS.put("port_of_loading", "선적항");
        FIELDS.put("transshipment_port", "환적항");
        FIELDS.put("reference_no", "증명서 번호");
        FIELDS.put("readability", "판독 상태");
        FIELDS.put("gross_weight_kg", "총중량");
        FIELDS.put("net_weight_kg", "순중량");
    }

    private static final String B = "(?:\\*\\*)?"; // 마크다운 굵게 표시가 끼어 있어도 맞춘다
    private static final String T = "`?"; // 코드 표시(`…`)도 벗긴다
    private static final Pattern RETRO = Pattern.compile(
            B + T + "issued_retroactively" + T + B + "\\s*(?:는|은|가|이)?\\s*" + B + "\\s*" + T + "(true|false)" + T + B
                    + "\\s*(입니다|이기|이고|이며|이므로|이어서|이다|임)");
    /** [] · ["…", "…"] · [CO_X, CO_Y] 만 목록으로 본다('[사내 메모]' 같은 머리말은 건드리지 않는다) */
    private static final Pattern LIST = Pattern.compile(T + "\\[\\s*("
            + "|\"[^\"\\]\\n]{0,80}\"(?:\\s*,\\s*\"[^\"\\]\\n]{0,80}\")*"
            + "|[A-Z]{2,}(?:_[A-Z]+)+(?:\\s*,\\s*[A-Z]{2,}(?:_[A-Z]+)+)*"
            + ")\\s*\\]" + T);
    /** 밑줄이 있는 키(issued_retroactively)는 어디서든, 한 단어 키(reason · fulfilled)는 굵게·백틱 안이거나 바로 뒤에 조사가 올 때만 바꾼다(영문 메일 본문의 'for this reason' 은 그대로) */
    private static final Pattern FIELD = Pattern.compile(T + "\\b("
            + FIELDS.keySet().stream().filter(k -> k.contains("_")).collect(Collectors.joining("|"))
            + "|(?:(?<=\\*\\*|`)(?:" + FIELDS.keySet().stream().filter(k -> !k.contains("_")).collect(Collectors.joining("|")) + ")(?=\\*\\*|`|:))"
            + "|(?:" + FIELDS.keySet().stream().filter(k -> !k.contains("_")).collect(Collectors.joining("|")) + ")(?=[은는이가을를의와과로]))"
            + "\\b" + T);
    private static final Pattern CODE_WORD = Pattern.compile("(?:\\d단계\\s*)?코드\\s+(?=" + B + T + "\"?[A-Z]{2,}_)");
    private static final Pattern CODE_NOUN = Pattern.compile("(?:불일치|mismatch)\\s*코드");
    private static final Pattern REASON_NOUN = Pattern.compile("사유\\s*코드");
    private static final Pattern CODE = Pattern.compile("`?\"?\\b([A-Z]{2,}(?:_[A-Z]+)+)\\b\"?`?");
    private static final Pattern NONE_DUP = Pattern.compile("없음\\s*\\(\\s*`?없음`?\\s*\\)");
    private static final Pattern BOOL = Pattern.compile("\\b(true|false)\\b");
    private static final Pattern NONE_BOLD = Pattern.compile("\\*\\*없음\\*\\*\\s*입니다");
    private static final Pattern NONE = Pattern.compile("없음\\s*입니다");
    /** '- 빠진 서류는 없습니다.' · '- **빠진 서류**: 없음' 처럼 빠진 서류가 없다는 말만 있는 목록 줄 */
    private static final Pattern EMPTY_MISSING_LINE = Pattern.compile(
            "(?m)^[ \\t]*[-*][ \\t]*" + B + "빠진 서류" + B + "[은는가이]?[ \\t]*[:：]?" + B + "[ \\t]*" + B
                    + "(?:없음|없습니다)" + B + "[^\\n]{0,8}(?:\\n|$)");

    private PlainText() {
    }

    public static String humanize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = text;
        s = replace(s, RETRO, (m, in) -> {
            int bolds = m.group().split("\\*\\*", -1).length - 1;
            // 굵게 표시를 짝이 안 맞게 지웠으면 하나 돌려놓는다('…늦고 issued_retroactively는 false**이기' 의 닫는 **)
            return "소급발급 표시가 " + retroTail(m.group(1), m.group(2)) + (bolds % 2 == 1 ? "**" : "");
        }, false);
        s = replace(s, LIST, (m, in) -> listText(m.group(1)), false);
        s = replace(s, FIELD, (m, in) -> FIELDS.get(m.group(1)), true);
        s = replace(s, CODE_WORD, (m, in) -> "", false);
        s = replace(s, CODE_NOUN, (m, in) -> "불일치 항목", true);
        s = replace(s, REASON_NOUN, (m, in) -> "사유", true);
        s = replace(s, CODE, PlainText::codeText, true);
        s = replace(s, BOOL, (m, in) -> m.group(1).equals("true") ? "있음" : "없음", true);
        s = NONE_DUP.matcher(s).replaceAll("없음");
        s = NONE_BOLD.matcher(s).replaceAll("**없습니다**");
        s = NONE.matcher(s).replaceAll("없습니다");
        s = EMPTY_MISSING_LINE.matcher(s).replaceAll("");
        return s;
    }

    /** issued_retroactively 는 false + 어미 → '소급발급 표시가 없습니다' 처럼 어미까지 맞춘다 */
    private static String retroTail(String bool, String ending) {
        String stem = bool.equals("true") ? "있" : "없";
        return switch (ending) {
            case "입니다" -> stem + "습니다";
            case "이기" -> stem + "기";
            case "이고" -> stem + "고";
            case "이며" -> stem + "으며";
            case "이므로" -> stem + "으므로";
            case "이어서" -> stem + "어서";
            case "이다" -> stem + "다";
            default -> stem + "음";
        };
    }

    /** ["CO_X", "CO_Y"] → 라벨 목록, [] → 없음. 코드가 아닌 항목(서류 이름 등)은 그대로 */
    private static String listText(String inner) {
        String body = inner == null ? "" : inner.trim();
        if (body.isEmpty()) {
            return "없음";
        }
        return Arrays.stream(body.split(","))
                .map(x -> x.trim().replaceAll("^\"|\"$", "").trim())
                .filter(x -> !x.isEmpty())
                .map(x -> MismatchCode.find(x).map(MismatchCode::label).orElse(x))
                .collect(Collectors.joining(", "));
    }

    /** 코드 → 라벨. 굵게(**…**) 안이면 그대로, 아니면 작은따옴표로 묶어 이름처럼 읽히게 한다. 모르는 코드는 그대로 둔다 */
    private static String codeText(Matcher m, String in) {
        boolean bold = m.start() >= 2 && m.end() + 2 <= in.length()
                && in.startsWith("**", m.start() - 2) && in.startsWith("**", m.end());
        return MismatchCode.find(m.group(1))
                .map(c -> bold ? c.label() : "‘" + c.label() + "’")
                .orElse(m.group());
    }

    @FunctionalInterface
    private interface Rep {
        String apply(Matcher m, String input);
    }

    /**
     * 패턴마다 바꾸고, {@code fixJosa} 면 바꾼 말 바로 뒤의 조사를 받침에 맞춘다.
     */
    private static String replace(String s, Pattern p, Rep f, boolean fixJosa) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String rep = f.apply(m, s);
            out.append(s, last, m.start()).append(rep);
            last = m.end();
            if (fixJosa) {
                last = appendJosa(s, last, rep, out);
            }
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    /** s[pos..] 가 (굵게·따옴표 뒤) 조사로 시작하면 rep 의 받침에 맞춰 바꿔 붙이고 다음 위치를 돌려준다 */
    private static int appendJosa(String s, int pos, String rep, StringBuilder out) {
        int i = pos;
        while (i < s.length() && (s.startsWith("**", i) || "\"'’”".indexOf(s.charAt(i)) >= 0)) {
            i += s.startsWith("**", i) ? 2 : 1;
        }
        Boolean batchim = batchim(rep);
        if (batchim == null || i >= s.length()) {
            return pos;
        }
        String[][] pairs = {{"은", "는"}, {"을", "를"}, {"과", "와"}, {"이", "가"}};
        for (String[] pr : pairs) {
            String found = s.startsWith(pr[0], i) ? pr[0] : s.startsWith(pr[1], i) ? pr[1] : null;
            if (found == null) {
                continue;
            }
            if (pr[0].equals("이") && found.equals("이") && i + 1 < s.length() && "고며므어기다라입었".indexOf(s.charAt(i + 1)) >= 0) {
                return pos; // '이고' · '이며' 같은 서술격 조사는 그대로
            }
            if (found.equals("이") && i + 1 < s.length() && s.charAt(i + 1) == '다') {
                return pos;
            }
            out.append(s, pos, i).append(batchim ? pr[0] : pr[1]);
            return i + found.length();
        }
        if (s.startsWith("으로", i) || (s.startsWith("로", i))) {
            String found = s.startsWith("으로", i) ? "으로" : "로";
            boolean rieul = lastSyllable(rep) != null && (lastSyllable(rep) - 0xAC00) % 28 == 8;
            out.append(s, pos, i).append(batchim && !rieul ? "으로" : "로");
            return i + found.length();
        }
        return pos;
    }

    /** 마지막 한글 음절에 받침이 있으면 true, 없으면 false, 한글로 끝나지 않으면 null */
    private static Boolean batchim(String rep) {
        Character c = lastSyllable(rep);
        return c == null ? null : (c - 0xAC00) % 28 != 0;
    }

    private static Character lastSyllable(String rep) {
        String t = rep.replaceAll("[\\s'’‘\"*]+$", "");
        if (t.isEmpty()) {
            return null;
        }
        char c = t.charAt(t.length() - 1);
        return c >= 0xAC00 && c <= 0xD7A3 ? c : null;
    }
}
