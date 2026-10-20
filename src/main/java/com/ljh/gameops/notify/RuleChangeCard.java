// 「AI 가 분류 규칙을 고쳤어요」 카드 내용 — 바뀐 키워드, 시험 결과, 누가·왜, 되돌릴 수 있는지
package com.ljh.gameops.notify;

import java.time.OffsetDateTime;
import java.util.List;

/** @param active 지금 쓰는 판이면 true(되돌리기 버튼을 보인다). @param lastAction 방금 사람이 한 일(되돌림 등) */
public record RuleChangeCard(long versionId, boolean active, String status, String actor, String reason, List<String> changes,
                             double devBefore, double devAfter, int regressionTotal, OffsetDateTime createdAt, String lastAction) {
    public RuleChangeCard withLastAction(String action) {
        return new RuleChangeCard(versionId, active, status, actor, reason, changes, devBefore, devAfter, regressionTotal, createdAt, action);
    }

    public static RuleChangeCard of(com.ljh.gameops.classify.RuleVersion v) {
        return new RuleChangeCard(v.getId(), v.getStatus() == com.ljh.gameops.classify.RuleVersion.Status.ACTIVE, v.getStatus().name(),
                v.getActor(), v.getReason(), List.of(v.getSummary().split("\n")), v.getDevBefore(), v.getDevAfter(),
                v.getRegressionTotal(), v.getCreatedAt(), null);
    }
}
