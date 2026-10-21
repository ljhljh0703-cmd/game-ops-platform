// Jira 에서 완료되면 운영 이슈 해결(결정 32) — 만든 티켓을 1분마다 보고, 완료 묶음(Done 등)으로 옮겨졌으면
// 운영 이슈를 해결로 바꾼다. 그러면 늘 하던 대로 슬랙 카드가 초록이 되고 디스코드 제보자에게 「고쳤어요」가 간다.
// Jira 에서 완료로 옮기는 것이 QA·개발팀의 확인이라, 슬랙에서 따로 누르지 않는다. 누가 옮겼는지는 기록에 남는다
package com.ljh.gameops.jira;

import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class JiraWatcher {
    private static final Logger log = LoggerFactory.getLogger(JiraWatcher.class);
    private static final Set<IssueStatus> CLOSABLE = Set.of(IssueStatus.OPEN, IssueStatus.IN_PROGRESS, IssueStatus.VERIFYING);

    private final JiraClient jira;
    private final JiraTickets tickets;
    private final IssueRepository issueRepo;
    private final IssueService issues;

    public JiraWatcher(JiraClient jira, JiraTickets tickets, IssueRepository issueRepo, IssueService issues) {
        this.jira = jira;
        this.tickets = tickets;
        this.issueRepo = issueRepo;
        this.issues = issues;
    }

    @Scheduled(initialDelayString = "${gameops.jira.watch-ms:60000}", fixedDelayString = "${gameops.jira.watch-ms:60000}")
    public void tick() {
        if (jira.ready()) scan();
    }

    /** 이번에 해결한 운영 이슈 번호 */
    public List<Long> scan() {
        List<Long> done = new ArrayList<>();
        for (JiraTickets.Ticket t : tickets.notDone()) {
            JiraClient.Status s;
            try {
                s = jira.status(t.key());
            } catch (RuntimeException e) {
                log.debug("Jira 보기 실패 {}: {}", t.key(), e.getMessage());
                continue;
            }
            if (!s.done() || !tickets.markDone(t.issueId())) continue;
            var issue = issueRepo.findById(t.issueId());
            if (issue.isEmpty() || !CLOSABLE.contains(issue.get().getStatus())) continue;
            issues.changeStatus(t.issueId(), IssueStatus.RESOLVED, "jira:" + (s.by() == null ? t.key() : s.by() + " · " + t.key()),
                    "jira:" + t.key() + " " + s.name());
            done.add(t.issueId());
        }
        if (!done.isEmpty()) log.info("Jira 완료로 해결: {}", done);
        return done;
    }
}
