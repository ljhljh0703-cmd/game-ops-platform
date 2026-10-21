// 대기열 한 건을 사람이 읽을 알림 문구로 바꾼다
package com.ljh.gameops.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.issue.Issue;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.Severity;
import org.springframework.stereotype.Component;

@Component
public class NotificationRenderer {
    private final ObjectMapper mapper;
    private final IssueRepository issues;
    private final GameRepository games;
    private final IssueCardFactory cards;
    private final com.ljh.gameops.classify.RuleVersionRepository versions;
    private final com.ljh.gameops.feedback.FeedbackService feedback;
    private final DigestCardFactory digestCards;

    public NotificationRenderer(ObjectMapper mapper, IssueRepository issues, GameRepository games, IssueCardFactory cards,
                                com.ljh.gameops.classify.RuleVersionRepository versions, com.ljh.gameops.feedback.FeedbackService feedback,
                                DigestCardFactory digestCards) {
        this.feedback = feedback;
        this.digestCards = digestCards;
        this.mapper = mapper;
        this.issues = issues;
        this.games = games;
        this.cards = cards;
        this.versions = versions;
    }

    public RenderedNotification render(OutboxMessage m) {
        JsonNode p;
        try {
            p = mapper.readTree(m.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException("알림 내용 해석 실패: " + m.getId(), e);
        }
        return switch (m.getType()) {
            case NEW_ISSUE -> issueLine(m.getType(), p.path("issueId").asLong(), newIssueLabel(p),
                    p.hasNonNull("detachedFrom") ? p.path("detachedFrom").asLong() : null);
            case SEVERITY_RAISED -> issueLine(m.getType(), p.path("issueId").asLong(),
                    "심각도 상승(" + p.path("from").asText() + "→)", null);
            case WEEKLY_REPORT -> new RenderedNotification(m.getType(), p.path("gameCode").asText(null), Severity.LOW, null, p.path("text").asText());
            // 게임이 없는 운영 알림 — 높음으로 두어 공용 긴급 채널로 간다
            // 플레이어 답장은 대기열에서 디스코드로 바로 보낸다(OutboxDispatcher). 여기는 기록·로그용 한 줄
            case PLAYER_RESOLVED -> new RenderedNotification(m.getType(), null, Severity.LOW, p.path("issueId").asLong(),
                    "제보자에게 해결 답장 #" + p.path("issueId").asLong());
            // 규칙 반영 — 게임이 없는 운영 알림이라 공용 채널. 보낼 때의 상태로 그린다(그사이 되돌렸으면 되돌린 모양)
            case RULES_CHANGED -> {
                var v = versions.findById(p.path("versionId").asLong()).orElseThrow(() -> new IllegalStateException("규칙 판 없음"));
                yield new RenderedNotification(m.getType(), null, Severity.LOW, null,
                        "🤖 분류 규칙을 고쳤어요 #" + v.getId() + " · " + v.getSummary().replace("\n", " / "), null, RuleChangeCard.of(v));
            }
            case DIGEST -> {
                var d = feedback.get(p.path("digestId").asLong());
                yield new RenderedNotification(m.getType(), d.gameCode(), Severity.LOW, null,
                        "🧠 최근 피드백 정리 · " + (d.gameName() == null ? "전체 게임" : d.gameName()), null, null,
                        digestCards.current(d.id()));
            }
            case SLACK_URL_CHANGED -> new RenderedNotification(m.getType(), null, Severity.HIGH, null, p.path("text").asText());
            case AI_BUDGET -> new RenderedNotification(m.getType(), null, Severity.HIGH, null, p.path("text").asText());
        };
    }

    private static String newIssueLabel(JsonNode p) {
        StringBuilder s = new StringBuilder("새 이슈");
        if (p.hasNonNull("recurrenceOf")) s.append(" · 재발 의심(최근 해결한 #").append(p.path("recurrenceOf").asLong()).append("과 비슷)");
        if (p.hasNonNull("detachedFrom")) s.append("(#").append(p.path("detachedFrom").asLong()).append("에서 떼어냄)");
        return s.toString();
    }

    private RenderedNotification issueLine(NotificationType type, long issueId, String label, Long detachedFrom) {
        Issue i = issues.findById(issueId).orElseThrow(() -> new IllegalStateException("이슈 없음: " + issueId));
        var game = games.findById(i.getGameId());
        String text = "[%s] %s #%d · %s · %s/%s · 제보 %d건".formatted(
                game.map(g -> g.getName()).orElse("?"), label, i.getId(), i.getTitle(), i.getCategory(), i.getSeverity(), i.getReportCount());
        // 카드 제목에는 짧은 종류만(「새 이슈」「심각도 상승」). 재발·떼어냄은 카드 아래 줄에 따로 나온다
        String cardLabel = type == NotificationType.SEVERITY_RAISED ? "심각도 상승" : "새 이슈";
        return new RenderedNotification(type, game.map(g -> g.getCode()).orElse(null), i.getSeverity(), i.getId(), text,
                cards.build(issueId, cardLabel, detachedFrom));
    }
}
