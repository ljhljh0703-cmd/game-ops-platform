// 분류·심각도·상태를 사람이 읽는 말로 — 슬랙·디스코드 카드와 「누가 무엇을 했나」 문구가 같은 말을 쓰게 한 곳에 둔다
package com.ljh.gameops.issue;

import java.util.Map;

public final class Labels {
    public static final Map<Category, String> CATEGORY = Map.of(
            Category.CRASH, "크래시", Category.BUG, "버그", Category.PERFORMANCE, "성능", Category.BALANCE, "밸런스",
            Category.PAYMENT, "결제", Category.UX, "불편", Category.OTHER, "기타");
    public static final Map<Severity, String> SEVERITY = Map.of(
            Severity.CRITICAL, "긴급", Severity.HIGH, "높음", Severity.MEDIUM, "보통", Severity.LOW, "낮음");
    public static final Map<IssueStatus, String> STATUS = Map.of(
            IssueStatus.OPEN, "열림", IssueStatus.IN_PROGRESS, "진행 중", IssueStatus.RESOLVED, "해결됨", IssueStatus.WONT_FIX, "보류");

    private Labels() {}

    public static String of(Category c, Severity s) { return CATEGORY.get(c) + "/" + SEVERITY.get(s); }

    /** 상태를 바꿨을 때 카드에 남는 말 */
    public static String statusVerb(IssueStatus next) {
        return switch (next) {
            case RESOLVED -> "✅ 해결됨";
            case WONT_FIX -> "⏸️ 보류";
            case IN_PROGRESS -> "🛠️ 진행 중";
            case OPEN -> "↩️ 다시 열림";
        };
    }
}
