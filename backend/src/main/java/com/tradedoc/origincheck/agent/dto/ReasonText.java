package com.tradedoc.origincheck.agent.dto;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 판정 reason 다듬기 */
final class ReasonText {

    /**
     * 설정 7 은 reason 끝에 품목마다 'PSR 품명: … → 충족', 'HS LED …: … → 같음' 을 붙인다(줄을 바꾸기도, 문단에 잇기도 한다).
     * 줄 처음이나 문장 사이에서 'PSR ' · 'HS ' 로 시작해 80자 안에 ':' 가 나오는 첫 자리부터 끝까지를 품목별 근거로 본다.
     */
    private static final Pattern ITEM_TAIL = Pattern.compile("(?:^|(?<=[\\s.,]))(?:PSR|HS)\\s[^\\n:]{1,80}:", Pattern.MULTILINE);

    private ReasonText() {
    }

    /** 품목별 근거 꼬리를 뗀 reason. 떼고 나서 아무것도 안 남으면 원문 그대로 */
    static String withoutItemTail(String reason) {
        if (reason == null || reason.isBlank()) {
            return reason;
        }
        Matcher m = ITEM_TAIL.matcher(reason);
        if (!m.find()) {
            return reason;
        }
        String head = reason.substring(0, m.start()).strip().replaceAll("[,;]+$", "").strip();
        return head.isEmpty() ? reason : closeSentence(head);
    }

    /** 연결 어미('…0일이며', '…40%이므로')에서 끊긴 문장을 맺는다. 모르는 끝이면 마지막 완결 문장까지만 남긴다 */
    private static final String[][] ENDINGS = {
            {"있으며", "있습니다."}, {"없으며", "없습니다."}, {"이므로", "입니다."}, {"이며", "입니다."},
            {"이고", "입니다."}, {"하며", "합니다."}, {"하고", "합니다."}, {"되며", "됩니다."}, {"보며", "봅니다."},
    };

    static String closeSentence(String s) {
        if (s.endsWith(".") || s.endsWith("다")) {
            return s;
        }
        for (String[] e : ENDINGS) {
            if (s.endsWith(e[0])) {
                return s.substring(0, s.length() - e[0].length()) + e[1];
            }
        }
        int last = s.lastIndexOf("다.");
        return last > 0 ? s.substring(0, last + 2) : s;
    }
}
