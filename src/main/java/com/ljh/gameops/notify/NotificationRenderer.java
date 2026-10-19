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

    public NotificationRenderer(ObjectMapper mapper, IssueRepository issues, GameRepository games) {
        this.mapper = mapper;
        this.issues = issues;
        this.games = games;
    }

    public RenderedNotification render(OutboxMessage m) {
        JsonNode p;
        try {
            p = mapper.readTree(m.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException("알림 내용 해석 실패: " + m.getId(), e);
        }
        return switch (m.getType()) {
            case NEW_ISSUE -> issueLine(m.getType(), p.path("issueId").asLong(), newIssueLabel(p));
            case SEVERITY_RAISED -> issueLine(m.getType(), p.path("issueId").asLong(),
                    "심각도 상승(" + p.path("from").asText() + "→)");
            case WEEKLY_REPORT -> new RenderedNotification(m.getType(), Severity.LOW, null, p.path("text").asText());
        };
    }

    private static String newIssueLabel(JsonNode p) {
        StringBuilder s = new StringBuilder("새 이슈");
        if (p.hasNonNull("detachedFrom")) s.append("(#").append(p.path("detachedFrom").asLong()).append("에서 떼어냄)");
        return s.toString();
    }

    private RenderedNotification issueLine(NotificationType type, long issueId, String label) {
        Issue i = issues.findById(issueId).orElseThrow(() -> new IllegalStateException("이슈 없음: " + issueId));
        String game = games.findById(i.getGameId()).map(g -> g.getName()).orElse("?");
        String text = "[%s] %s #%d · %s · %s/%s · 제보 %d건".formatted(
                game, label, i.getId(), i.getTitle(), i.getCategory(), i.getSeverity(), i.getReportCount());
        return new RenderedNotification(type, i.getSeverity(), i.getId(), text);
    }
}
