// 제보 접수 흐름 — 분류(외부 호출)는 트랜잭션 밖에서, 저장·중복 묶기·알림 대기열은 한 트랜잭션 안에서
package com.ljh.gameops.intake;

import com.ljh.gameops.classify.Classification;
import com.ljh.gameops.classify.IssueClassifier;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class IntakeService {
    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);
    private static final EnumSet<IssueStatus> OPEN = EnumSet.of(IssueStatus.OPEN, IssueStatus.IN_PROGRESS);
    private static final int MAX_TRIES = 3;

    private final GameRepository games;
    private final ReportRepository reports;
    private final IssueRepository issues;
    private final IssueReportRepository links;
    private final IssueClassifier classifier;
    private final OutboxService outbox;
    private final AuthorHasher hasher;
    private final IssueProperties props;
    private final TransactionTemplate tx;
    private final EntityManager em;

    public IntakeService(GameRepository games, ReportRepository reports, IssueRepository issues,
                         IssueReportRepository links, IssueClassifier classifier, OutboxService outbox,
                         AuthorHasher hasher, IssueProperties props, TransactionTemplate tx,
                         EntityManager em) {
        this.games = games;
        this.reports = reports;
        this.issues = issues;
        this.links = links;
        this.classifier = classifier;
        this.outbox = outbox;
        this.hasher = hasher;
        this.props = props;
        this.tx = tx;
        this.em = em;
    }

    public IntakeResult submit(SubmitReport cmd) {
        Game game = games.findByCode(cmd.gameCode())
                .orElseThrow(() -> new NotFoundException("등록되지 않은 게임: " + cmd.gameCode()));

        Optional<IntakeResult> redelivered = findRedelivery(cmd);
        if (redelivered.isPresent()) return redelivered.get();

        Classification c = classifier.classify(game.getCode(), cmd.body());

        // 동시 제보가 같은 이슈를 두 번 만들거나(고유 인덱스), 같은 이슈를 동시에 고치면(version) 다시 시도한다.
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> store(game, cmd, c));
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException
                     | PessimisticLockingFailureException e) {
                Optional<IntakeResult> again = findRedelivery(cmd);
                if (again.isPresent()) return again.get();
                if (attempt >= MAX_TRIES) throw e;
                log.info("제보 저장 경합, 다시 시도 {}/{}: {}", attempt, MAX_TRIES, e.getClass().getSimpleName());
            }
        }
    }

    private Optional<IntakeResult> findRedelivery(SubmitReport cmd) {
        if (cmd.externalId() == null) return Optional.empty();
        return reports.findBySourceChannelAndExternalId(cmd.channel(), cmd.externalId())
                .flatMap(r -> links.findByReportId(r.getId())
                        .map(l -> new IntakeResult(r.getId(), l.getIssueId(),
                                IntakeResult.Outcome.DUPLICATE_DELIVERY, l.getSimilarity())));
    }

    private record Match(Issue issue, double score) {}

    /**
     * 최근 해결한 이슈 중 이 글과 비슷한 것이 있으면 재발로 본다. 닫힌 이슈에 다시 붙이지는 않는다
     * (고친 뒤 다시 생긴 건 새 사건이고, 원인이 다를 수 있다). 묶기와 같은 기준값을 쓴다.
     */
    private Optional<Match> findRecurrence(Long gameId, String key, String normalized) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(props.recurrenceWindowDays());
        Match best = null;
        for (Issue i : issues.findByGameIdAndStatusAndResolvedAtAfter(gameId, IssueStatus.RESOLVED, since)) {
            double s = i.getNormalizedKey().equals(key) ? 1.0 : Similarity.jaccard(normalized, i.getNormalizedText());
            if (s >= props.duplicateThreshold() && (best == null || s > best.score()
                    || (s == best.score() && i.getResolvedAt().isAfter(best.issue().getResolvedAt())))) {
                best = new Match(i, s);
            }
        }
        return Optional.ofNullable(best);
    }

    private IntakeResult store(Game game, SubmitReport cmd, Classification c) {
        Report report = reports.saveAndFlush(new Report(game.getId(), cmd.channel(), cmd.externalId(),
                hasher.hash(cmd.channel().name(), cmd.authorId()), cmd.body(), cmd.attachmentUrls()));

        String normalized = TextNormalizer.normalize(cmd.body());
        // 링크·이모지·감탄사만 있는 글은 정규화하면 빈 문자열이 된다. 빈 글끼리 묶으면 무관한 제보가 한 이슈로
        // 몰리므로, 빈 글은 비교하지 않고 제보마다 따로 이슈를 만든다.
        boolean empty = normalized.isEmpty();
        String key = TextNormalizer.key(empty ? "empty-report:" + report.getId() : normalized);

        Issue best = null;
        double bestScore = 0;
        if (!empty) {
            Optional<Issue> exact = issues.findByGameIdAndNormalizedKeyAndStatusIn(game.getId(), key, OPEN);
            if (exact.isPresent()) {
                best = exact.get();
                bestScore = 1.0;
            } else {
                for (Issue i : issues.findByGameIdAndStatusIn(game.getId(), OPEN)) {
                    double s = Similarity.jaccard(normalized, i.getNormalizedText());
                    if (s > bestScore) { bestScore = s; best = i; }
                }
            }
        }

        if (best != null && bestScore >= props.duplicateThreshold()) {
            // 이슈 한 줄을 잠그고 DB 최신 값으로 다시 읽는다. 동시에 붙는 제보들이 차례로 줄을 서고,
            // 그사이 다른 요청이 닫은 이슈에는 붙이지 않는다.
            em.refresh(best, LockModeType.PESSIMISTIC_WRITE);
            if (!best.getStatus().isOpen()) {
                best = null;
            } else if (!best.getNormalizedKey().equals(key)) {
                // 잠그기 전에 사람이 대표 제보를 바꿨을 수 있다. 최신 대표 글로 다시 잰다.
                bestScore = Similarity.jaccard(normalized, best.getNormalizedText());
            }
        }
        if (best != null && bestScore >= props.duplicateThreshold()) {
            var before = best.getSeverity();
            best.attachReport(c.severity());
            issues.saveAndFlush(best);
            links.save(new IssueReport(best.getId(), report.getId(), bestScore));
            if (best.getSeverity() != before) {
                outbox.enqueue(NotificationType.SEVERITY_RAISED, Map.of("issueId", best.getId(), "from", before.name()));
            }
            return new IntakeResult(report.getId(), best.getId(), IntakeResult.Outcome.ATTACHED_TO_EXISTING, bestScore);
        }

        Issue fresh = new Issue(game.getId(), c.title(), c.category(), c.severity(),
                key, normalized, report.getId(), c.classifiedBy());
        if (!empty) findRecurrence(game.getId(), key, normalized).ifPresent(m -> fresh.markRecurrenceOf(m.issue().getId(), m.score()));
        Issue created = issues.saveAndFlush(fresh);
        links.save(new IssueReport(created.getId(), report.getId(), 1.0));
        Map<String, Object> payload = new HashMap<>();
        payload.put("issueId", created.getId());
        if (created.getRecurrenceOf() != null) payload.put("recurrenceOf", created.getRecurrenceOf());
        outbox.enqueue(NotificationType.NEW_ISSUE, payload);
        return new IntakeResult(report.getId(), created.getId(), IntakeResult.Outcome.NEW_ISSUE, 1.0);
    }
}
