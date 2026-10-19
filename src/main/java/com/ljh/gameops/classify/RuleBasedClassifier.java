// 키워드 규칙 분류기 — 외부 호출 없이 항상 답을 낸다(기본값이자 내려가기 대상)
package com.ljh.gameops.classify;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RuleBasedClassifier implements IssueClassifier {

    private record Rule(Category category, Severity severity, List<String> keywords) {}

    // 위에서부터 먼저 걸리는 규칙을 쓴다. 더 심각한 것을 위에 둔다.
    private static final List<Rule> RULES = List.of(
            new Rule(Category.PAYMENT, Severity.CRITICAL, List.of("결제", "환불", "충전", "구매했는데", "payment", "refund", "purchase")),
            new Rule(Category.CRASH, Severity.HIGH, List.of("튕", "크래시", "강제 종료", "강종", "멈춤", "먹통", "접속 불가", "접속이 안", "crash", "freeze")),
            new Rule(Category.PERFORMANCE, Severity.MEDIUM, List.of("렉", "느려", "프레임", "버벅", "로딩이 길", "lag", "fps")),
            new Rule(Category.BUG, Severity.MEDIUM, List.of("버그", "오류", "안 돼", "안돼", "안 됨", "안됨", "이상해", "깨져", "bug", "error")),
            new Rule(Category.BALANCE, Severity.LOW, List.of("밸런스", "너무 쎄", "너무 세", "너무 약", "사기", "너프", "버프", "op ", "balance")),
            new Rule(Category.UX, Severity.LOW, List.of("불편", "헷갈", "못 찾", "ui", "버튼", "글씨"))
    );

    @Override
    public Classification classify(String gameCode, String body) {
        String text = body.toLowerCase(Locale.ROOT) + " ";
        for (Rule rule : RULES) {
            for (String kw : rule.keywords()) {
                if (text.contains(kw)) {
                    return new Classification(rule.category(), rule.severity(), Titles.fromBody(body), "rules");
                }
            }
        }
        return new Classification(Category.OTHER, Severity.LOW, Titles.fromBody(body), "rules");
    }

    /** 규칙 목록을 읽기 전용으로 보여 준다(문서·테스트용). */
    public static Map<Category, List<String>> keywordTable() {
        Map<Category, List<String>> m = new LinkedHashMap<>();
        RULES.forEach(r -> m.put(r.category(), r.keywords()));
        return m;
    }
}
