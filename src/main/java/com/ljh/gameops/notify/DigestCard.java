// 「🧠 최근 피드백 정리」 카드 내용 — AI 가 쓴 정리 글과 범위(게임·기간·제보 수).
// handoffs = 개발 AI에게 넘긴 이슈 링크(슬랙 서식 <주소|이름>). 비어 있으면 카드에 「개발 AI에게 넘기기」 버튼, 있으면 넘긴 기록
package com.ljh.gameops.notify;

import java.time.OffsetDateTime;
import java.util.List;

/** jira = 이 정리에서 만든 Jira 티켓, jiraAvailable = Jira 로 보낼 수 있는 게임이 있어 버튼을 보일지(결정 32) */
/** fixes = 개발 AI 수정 진행(결정 49 — 러너가 켜져 있을 때). runner = 러너가 켜져 있어 넘기기 버튼 대신 수정안을 기다리는지 */
public record DigestCard(long digestId, String gameName, int days, int reportCount, String text, String actor, OffsetDateTime createdAt,
                         List<Handoff> handoffs, List<Handoff> jira, boolean jiraAvailable,
                         List<com.ljh.gameops.handoff.DevFixService.Fix> fixes, boolean runner) {
    public record Handoff(String url, String label, String actor, OffsetDateTime at) {}

    public DigestCard {
        handoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
        jira = jira == null ? List.of() : List.copyOf(jira);
        fixes = fixes == null ? List.of() : List.copyOf(fixes);
    }

    public DigestCard(long digestId, String gameName, int days, int reportCount, String text, String actor, OffsetDateTime createdAt,
                      List<Handoff> handoffs, List<Handoff> jira, boolean jiraAvailable) {
        this(digestId, gameName, days, reportCount, text, actor, createdAt, handoffs, jira, jiraAvailable, List.of(), false);
    }

    public DigestCard(long digestId, String gameName, int days, int reportCount, String text, String actor, OffsetDateTime createdAt,
                      List<Handoff> handoffs) {
        this(digestId, gameName, days, reportCount, text, actor, createdAt, handoffs, List.of(), false);
    }

    public DigestCard(long digestId, String gameName, int days, int reportCount, String text, String actor, OffsetDateTime createdAt) {
        this(digestId, gameName, days, reportCount, text, actor, createdAt, List.of());
    }
}
