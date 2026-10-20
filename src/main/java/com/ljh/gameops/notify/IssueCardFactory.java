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

    public IssueCardFactory(IssueRepository issues, GameRepository games, ReportRepository reports) {
        this.issues = issues;
        this.games = games;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public IssueCard build(long issueId, String label, Long detachedFrom) {
        Issue i = issues.findById(issueId).orElseThrow(() -> new IllegalStateException("이슈 없음: " + issueId));
        String game = games.findById(i.getGameId()).map(Game::getName).orElse("?");
        Report rep = i.getRepresentativeReportId() == null ? null : reports.findById(i.getRepresentativeReportId()).orElse(null);
        return new IssueCard(i.getId(), game, label, i.getTitle(), rep == null ? i.getTitle() : rep.getBody(),
                i.getCategory(), i.getSeverity(), i.getStatus(), i.getReportCount(), i.getRecurrenceOf(), detachedFrom,
                rep == null ? null : rep.getSourceChannel(), i.getCreatedAt(), null);
    }

    /** 사람이 버튼을 누른 뒤 다시 그릴 때 — 알림 종류 대신 이슈 번호만 제목에 둔다 */
    public IssueCard current(long issueId) {
        return build(issueId, "이슈", null);
    }
}
