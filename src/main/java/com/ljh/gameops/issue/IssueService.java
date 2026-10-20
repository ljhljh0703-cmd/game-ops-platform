// 이슈 조회와 상태 변경 — 상태 변경은 감사 기록을 함께 남기고, 해결로 바뀌면 제보자 답장을 대기열에 넣는다
package com.ljh.gameops.issue;

import com.ljh.gameops.audit.AuditLog;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class IssueService {
    private final IssueRepository issues;
    private final IssueReportRepository links;
    private final GameRepository games;
    private final AuditLogRepository audit;
    private final OutboxService outbox;

    public IssueService(IssueRepository issues, IssueReportRepository links, GameRepository games, AuditLogRepository audit,
                        OutboxService outbox) {
        this.issues = issues;
        this.links = links;
        this.games = games;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<Issue> list(String gameCode, IssueStatus status) {
        Game game = games.findByCode(gameCode).orElseThrow(() -> new NotFoundException("등록되지 않은 게임: " + gameCode));
        return status == null
                ? issues.findByGameIdOrderByUpdatedAtDesc(game.getId())
                : issues.findByGameIdAndStatusOrderByUpdatedAtDesc(game.getId(), status);
    }

    @Transactional(readOnly = true)
    public Issue get(long id) {
        return issues.findById(id).orElseThrow(() -> new NotFoundException("이슈 없음: " + id));
    }

    @Transactional(readOnly = true)
    public List<IssueReport> reportsOf(long issueId) {
        return links.findByIssueId(issueId);
    }

    @Transactional
    public Issue changeStatus(long id, IssueStatus next, String actor, String via) {
        Issue issue = get(id);
        IssueStatus before = issue.getStatus();
        if (before == next) return issue;
        issue.changeStatus(next);
        issues.saveAndFlush(issue);
        audit.save(new AuditLog(actor, "ISSUE_STATUS", "issue:" + id, before + "->" + next + " via " + via));
        // 해결되면 디스코드에 제보한 사람들에게 「고쳤어요」. 이미 해결 상태면 위에서 돌아가므로 두 번 누르면 한 번만 간다.
        // 다시 열었다가 또 해결하면 새 해결이라 다시 알린다. 「보류」는 고친 게 아니라 보내지 않는다.
        if (next == IssueStatus.RESOLVED) {
            outbox.enqueue(NotificationType.PLAYER_RESOLVED, java.util.Map.of("issueId", id));
        }
        return issue;
    }
}
