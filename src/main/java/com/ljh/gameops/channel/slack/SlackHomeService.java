// 슬랙 홈 현황판 데이터 모으기 + 올리기 — 봇 홈 탭을 열 때와 「새로고침」을 누를 때
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.issue.Issue;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

@Service
public class SlackHomeService {
    private final GameRepository games;
    private final IssueRepository issues;
    private final JdbcTemplate jdbc;
    private final SlackWebApi web;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.metrics.OpsMetrics> metrics;
    private final org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.ai.AiUsage> usage;
    private final org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.ai.ApiQuota> quota;

    public SlackHomeService(GameRepository games, IssueRepository issues, JdbcTemplate jdbc, SlackWebApi web, Clock clock,
                            org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.metrics.OpsMetrics> metrics,
                            org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.ai.AiUsage> usage,
                            org.springframework.beans.factory.ObjectProvider<com.ljh.gameops.ai.ApiQuota> quota) {
        this.metrics = metrics;
        this.usage = usage;
        this.quota = quota;
        this.games = games;
        this.issues = issues;
        this.jdbc = jdbc;
        this.web = web;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SlackHome.Board board() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<SlackHome.GameBoard> boards = games.findAll().stream().sorted(Comparator.comparing(Game::getId)).map(g -> {
            List<Issue> open = issues.findByGameIdAndStatusIn(g.getId(), EnumSet.of(IssueStatus.OPEN, IssueStatus.IN_PROGRESS));
            long inProgress = open.stream().filter(i -> i.getStatus() == IssueStatus.IN_PROGRESS).count();
            long urgent = open.stream().filter(i -> i.getSeverity().ordinal() >= com.ljh.gameops.issue.Severity.HIGH.ordinal()).count();
            long resolved = issues.countResolvedBetween(g.getId(), now.minusDays(7), now.plusMinutes(1));
            // 먼저 볼 이슈: 심각한 것 → 제보 많은 것 → 최근
            List<SlackHome.IssueRow> top = open.stream()
                    .sorted(Comparator.comparing((Issue i) -> i.getSeverity().ordinal()).reversed()
                            .thenComparing(Comparator.comparing(Issue::getReportCount).reversed())
                            .thenComparing(Comparator.comparing(Issue::getUpdatedAt).reversed()))
                    .limit(SlackHome.TOP)
                    .map(i -> new SlackHome.IssueRow(i.getId(), i.getTitle(), i.getCategory(), i.getSeverity(), i.getStatus(), i.getReportCount()))
                    .toList();
            long verifying = issues.countByGameIdAndStatusIn(g.getId(), EnumSet.of(IssueStatus.VERIFYING));
            var m = metrics.getIfAvailable();
            String line = null;
            if (m != null) {
                var x = m.of(g.getCode(), 30);
                line = "최근 30일 · 고칠 때까지 " + com.ljh.gameops.metrics.OpsMetrics.short_(x.toFix(), x.fixedSamples())
                        + " · 재현 정보 " + x.reproPercent() + "% · 묶인 제보 " + x.merged() + "건 · 재발 " + x.recurrences() + "건";
            }
            return new SlackHome.GameBoard(g.getName(), open.size() - inProgress, inProgress, urgent, resolved, top, verifying, line);
        }).toList();
        String digest = jdbc.query("""
                SELECT d.created_at, g.name, d.report_count FROM feedback_digest d LEFT JOIN game g ON g.id = d.game_id
                ORDER BY d.id DESC LIMIT 1
                """, (rs, n) -> SlackCards.when(rs.getObject(1, OffsetDateTime.class)) + " · " + (rs.getString(2) == null ? "전체 게임" : rs.getString(2))
                + " · 제보 " + rs.getInt(3) + "건").stream().findFirst().orElse(null);
        String rules = jdbc.query("""
                SELECT id, created_at, status, summary FROM classifier_rule_version ORDER BY id DESC LIMIT 1
                """, (rs, n) -> "#" + rs.getLong(1) + " · " + SlackCards.when(rs.getObject(2, OffsetDateTime.class))
                + ("REVERTED".equals(rs.getString(3)) ? " · 되돌림" : "") + " · " + SlackCards.cut(rs.getString(4).replace("\n", " / "), 80))
                .stream().findFirst().orElse(null);
        var u = usage.getIfAvailable();
        var q = quota.getIfAvailable();
        String use = u == null ? null : u.month().line() + (q != null && q.github() != null ? " · " + q.github().line() : "");
        return new SlackHome.Board(boards, digest, rules, now, use);
    }

    /** 이 사람의 홈 탭에 현황판을 올린다 */
    public boolean publish(String userId) {
        return web.publishHome(userId, SlackHome.view(board()));
    }
}
