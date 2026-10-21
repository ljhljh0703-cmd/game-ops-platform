// 게임별 주간 운영 보고 문구 — 신규·해결·미해결 건수와 제보가 많은 미해결 상위 5건
package com.ljh.gameops.report;

import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.issue.Issue;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;

@Service
public class WeeklyReportService {
    private static final EnumSet<IssueStatus> OPEN = EnumSet.of(IssueStatus.OPEN, IssueStatus.IN_PROGRESS);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d");

    private final GameRepository games;
    private final IssueRepository issues;
    private final org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.metrics.OpsMetrics> metrics;

    public WeeklyReportService(GameRepository games, IssueRepository issues,
                               org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.metrics.OpsMetrics> metrics) {
        this.games = games;
        this.issues = issues;
        this.metrics = metrics;
    }

    /** [from, to) 구간 보고. to 는 보고 기준 시각(보통 월요일 0시). */
    @Transactional(readOnly = true)
    public String build(String gameCode, OffsetDateTime from, OffsetDateTime to) {
        Game g = games.findByCode(gameCode).orElseThrow(() -> new NotFoundException("등록되지 않은 게임: " + gameCode));
        long created = issues.countCreatedBetween(g.getId(), from, to);
        long resolved = issues.countResolvedBetween(g.getId(), from, to);
        long open = issues.countByGameIdAndStatusIn(g.getId(), OPEN);
        List<Issue> top = issues.findTop5ByGameIdAndStatusInOrderByReportCountDescUpdatedAtDesc(g.getId(), OPEN);

        StringBuilder sb = new StringBuilder();
        sb.append("[%s] 주간 운영 보고 (%s~%s)\n".formatted(g.getName(), DAY.format(from), DAY.format(to.minusDays(1))));
        sb.append("신규 이슈 %d건 · 해결 %d건 · 미해결 %d건\n".formatted(created, resolved, open));
        if (top.isEmpty()) {
            sb.append("미해결 이슈 없음");
        } else {
            sb.append("제보가 많은 미해결 이슈:\n");
            for (Issue i : top) {
                sb.append("• #%d %s · %s/%s · 제보 %d건\n".formatted(
                        i.getId(), i.getTitle(), i.getCategory(), i.getSeverity(), i.getReportCount()));
            }
        }
        // 처리 지표(결정 35) — 고칠 때까지·QA 확인까지·재현 정보·재발
        var m = metrics.getIfAvailable();
        if (m != null) {
            String t = m.of(gameCode, 7).text();
            sb = new StringBuilder(sb.toString().stripTrailing()).append("\n처리 지표:\n").append(t.substring(t.indexOf('\n') + 1).replace("*", ""));
        }
        return sb.toString().stripTrailing();
    }
}
