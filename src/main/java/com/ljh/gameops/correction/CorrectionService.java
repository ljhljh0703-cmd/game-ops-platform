// 사람이 자동 판단을 되돌리는 곳 — 잘못 묶인 제보 떼어내기, 분류·심각도 고치기.
// 고친 내용은 감사 기록과 별도로 「고친 기록」에 남겨, 규칙·기준값을 어디서 고칠지 근거로 쓴다.
package com.ljh.gameops.correction;

import com.ljh.gameops.audit.AuditLog;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.classify.Classification;
import com.ljh.gameops.classify.IssueClassifier;
import com.ljh.gameops.classify.Titles;
import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.issue.*;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import com.ljh.gameops.report.Report;
import com.ljh.gameops.report.ReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@Service
public class CorrectionService {
    private static final EnumSet<IssueStatus> OPEN = EnumSet.of(IssueStatus.OPEN, IssueStatus.IN_PROGRESS);

    private final IssueRepository issues;
    private final IssueReportRepository links;
    private final ReportRepository reports;
    private final GameRepository games;
    private final CorrectionRepository corrections;
    private final AuditLogRepository audit;
    private final OutboxService outbox;
    private final IssueClassifier classifier;
    private final TransactionTemplate tx;
    private final EntityManager em;

    public CorrectionService(IssueRepository issues, IssueReportRepository links, ReportRepository reports,
                             GameRepository games, CorrectionRepository corrections, AuditLogRepository audit,
                             OutboxService outbox, IssueClassifier classifier, TransactionTemplate tx, EntityManager em) {
        this.issues = issues;
        this.links = links;
        this.reports = reports;
        this.games = games;
        this.corrections = corrections;
        this.audit = audit;
        this.outbox = outbox;
        this.classifier = classifier;
        this.tx = tx;
        this.em = em;
    }

    public record DetachResult(Issue original, Issue created) {}

    /**
     * 잘못 묶인 제보 하나를 원래 이슈에서 떼어 새 이슈로 만든다.
     * 새 이슈의 분류는 그 제보 글만 보고 다시 판단한다. 원래 이슈의 심각도는 자동으로 내리지 않는다.
     */
    public DetachResult detach(long issueId, long reportId, String actor, String reason) {
        Report report = reports.findById(reportId).orElseThrow(() -> new NotFoundException("제보 없음: " + reportId));
        Game game = games.findById(report.getGameId()).orElseThrow();
        // 분류는 외부 호출일 수 있어 트랜잭션 밖에서 한다(접수 흐름과 같은 원칙).
        Classification c = classifier.classify(game.getCode(), report.getBody());

        return tx.execute(status -> {
            Issue original = lock(issueId);
            IssueReport link = links.findByReportId(reportId)
                    .filter(l -> l.getIssueId().equals(issueId))
                    .orElseThrow(() -> new NotFoundException("이 이슈에 묶인 제보가 아닙니다: issue " + issueId + ", report " + reportId));

            original.detachReport();
            links.delete(link);
            links.flush();

            // 대표 제보를 떼어냈다면 남은 제보 중 가장 먼저 묶인 것을 대표로 삼는다.
            // 이후 중복 비교는 새 대표 글로 하므로, 잘못 묶인 글이 계속 남의 제보를 끌어오지 않는다.
            if (original.getRepresentativeReportId().equals(reportId)) {
                IssueReport next = links.findByIssueId(issueId).stream()
                        .min(Comparator.comparing(IssueReport::getLinkedAt).thenComparing(IssueReport::getReportId))
                        .orElseThrow();
                Report rep = reports.findById(next.getReportId()).orElseThrow();
                String text = TextNormalizer.normalize(rep.getBody());
                String key = freeKey(game.getId(), original.getId(), text, "issue:" + original.getId(), rep.getId());
                original.replaceRepresentative(rep.getId(), Titles.fromBody(rep.getBody()), key, text);
            }
            issues.saveAndFlush(original);

            String text = TextNormalizer.normalize(report.getBody());
            String key = freeKey(game.getId(), null, text, "detached-report:" + reportId, reportId);
            Issue created = issues.saveAndFlush(new Issue(game.getId(), c.title(), c.category(), c.severity(),
                    key, text, reportId, c.classifiedBy()));
            links.save(new IssueReport(created.getId(), reportId, 1.0));

            corrections.save(new Correction(game.getId(), CorrectionKind.DETACH, issueId, reportId, created.getId(),
                    label(original), label(created), link.getSimilarity(), "similarity", actor, reason));
            audit.save(new AuditLog(actor, "ISSUE_DETACH", "issue:" + issueId,
                    "report " + reportId + " -> issue " + created.getId() + note(reason)));
            outbox.enqueue(NotificationType.NEW_ISSUE, Map.of("issueId", created.getId(), "detachedFrom", issueId));
            return new DetachResult(original, created);
        });
    }

    /** 사람이 분류·심각도를 고친다. 자동 판단은 심각도를 올리기만 하지만 사람은 내릴 수도 있다. */
    public Issue reclassify(long issueId, Category category, Severity severity, String actor, String reason) {
        if (category == null && severity == null) throw new IllegalArgumentException("category 나 severity 중 하나는 필요합니다");
        return tx.execute(status -> {
            Issue issue = lock(issueId);
            Category nextCat = category != null ? category : issue.getCategory();
            Severity nextSev = severity != null ? severity : issue.getSeverity();
            if (nextCat == issue.getCategory() && nextSev == issue.getSeverity()) return issue;

            String before = label(issue);
            Severity beforeSev = issue.getSeverity();
            String autoBy = issue.getClassifiedBy();
            issue.reclassify(nextCat, nextSev);
            issues.saveAndFlush(issue);

            corrections.save(new Correction(issue.getGameId(), CorrectionKind.RECLASSIFY, issueId, null, null,
                    before, label(issue), null, autoBy, actor, reason));
            audit.save(new AuditLog(actor, "ISSUE_RECLASSIFY", "issue:" + issueId, before + "->" + label(issue) + note(reason)));
            if (nextSev.ordinal() > beforeSev.ordinal()) {
                outbox.enqueue(NotificationType.SEVERITY_RAISED, Map.of("issueId", issueId, "from", beforeSev.name()));
            }
            return issue;
        });
    }

    public List<Correction> list(String gameCode) {
        return corrections.findTop200ByGameIdOrderByCreatedAtDesc(game(gameCode).getId());
    }

    public List<Correction> all(String gameCode) {
        return corrections.findByGameId(game(gameCode).getId());
    }

    private Game game(String code) {
        return games.findByCode(code).orElseThrow(() -> new NotFoundException("등록되지 않은 게임: " + code));
    }

    private Issue lock(long issueId) {
        Issue issue = em.find(Issue.class, issueId, LockModeType.PESSIMISTIC_WRITE);
        if (issue == null) throw new NotFoundException("이슈 없음: " + issueId);
        return issue;
    }

    /**
     * 열린 이슈끼리는 같은 정규화 키를 쓸 수 없다. 글 그대로의 키가 비어 있으면 그것을 쓰고,
     * 이미 다른 열린 이슈가 쓰고 있으면 이 이슈만의 키를 만든다(사람이 일부러 떼어낸 것이므로 다시 합치지 않는다).
     */
    private String freeKey(Long gameId, Long selfId, String normalized, String uniquePrefix, long reportId) {
        if (normalized.isEmpty()) return TextNormalizer.key("empty-report:" + reportId);
        String key = TextNormalizer.key(normalized);
        boolean taken = issues.findByGameIdAndNormalizedKeyAndStatusIn(gameId, key, OPEN)
                .filter(i -> !i.getId().equals(selfId))
                .isPresent();
        return taken ? TextNormalizer.key(uniquePrefix + ":" + normalized) : key;
    }

    static String label(Issue i) { return i.getCategory() + "/" + i.getSeverity(); }

    private static String note(String reason) { return reason == null || reason.isBlank() ? "" : " (" + reason + ")"; }
}
