// 이슈 하나의 지금 상태로 카드 내용을 만든다 — 처음 알림과, 버튼을 누른 뒤 카드를 다시 그릴 때 같은 곳을 쓴다
package com.ljh.gameops.notify;

import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.issue.Issue;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.report.Report;
import com.ljh.gameops.report.ReportRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IssueCardFactory {
    private final IssueRepository issues;
    private final GameRepository games;
    private final ReportRepository reports;
    private final com.ljh.gameops.issue.IssueReportRepository links;
    private final com.ljh.gameops.jira.JiraTickets jira;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.ljh.gameops.errors.ClientErrors clientErrors;

    public IssueCardFactory(IssueRepository issues, GameRepository games, ReportRepository reports,
                            com.ljh.gameops.issue.IssueReportRepository links, com.ljh.gameops.jira.JiraTickets jira) {
        this.issues = issues;
        this.games = games;
        this.reports = reports;
        this.links = links;
        this.jira = jira;
    }

    /** 이슈에 묶인 제보들의 재현 정보 */
    @Transactional(readOnly = true)
    public com.ljh.gameops.report.ReproInfo.Summary repro(long issueId) {
        var ids = links.findByIssueId(issueId).stream().map(com.ljh.gameops.issue.IssueReport::getReportId).toList();
        return com.ljh.gameops.report.ReproInfo.Summary.of(reports.findAllById(ids));
    }

    @Transactional(readOnly = true)
    public IssueCard build(long issueId, String label, Long detachedFrom) {
        Issue i = issues.findById(issueId).orElseThrow(() -> new IllegalStateException("이슈 없음: " + issueId));
        String game = games.findById(i.getGameId()).map(Game::getName).orElse("?");
        Report rep = i.getRepresentativeReportId() == null ? null : reports.findById(i.getRepresentativeReportId()).orElse(null);
        return new IssueCard(i.getId(), game, label, i.getTitle(), rep == null ? i.getTitle() : rep.getBody(),
                i.getCategory(), i.getSeverity(), i.getStatus(), i.getReportCount(), i.getRecurrenceOf(), detachedFrom,
                rep == null ? null : rep.getSourceChannel(), i.getCreatedAt(), null, repro(issueId),
                jira.of(issueId).map(com.ljh.gameops.jira.JiraTickets.Ticket::key).orElse(null),
                jira.of(issueId).map(com.ljh.gameops.jira.JiraTickets.Ticket::url).orElse(null),
                clientErrors == null ? null : com.ljh.gameops.errors.ClientErrors.cardLine(clientErrors.forIssue(issueId, 3)));
    }

    /** 사람이 버튼을 누른 뒤 다시 그릴 때 — 알림 종류 대신 이슈 번호만 제목에 둔다 */
    public IssueCard current(long issueId) {
        return build(issueId, "이슈", null);
    }
}
