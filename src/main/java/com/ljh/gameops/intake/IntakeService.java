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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
    private final org.springframework.context.ApplicationEventPublisher events;

    public IntakeService(GameRepository games, ReportRepository reports, IssueRepository issues,
                         IssueReportRepository links, IssueClassifier classifier, OutboxService outbox,
                         AuthorHasher hasher, IssueProperties props, TransactionTemplate tx,
                         EntityManager em, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.ljh.gameops.errors.ClientErrors clientErrors;

    /** 외국어 제보 한국어 번역(결정 42). 없으면 원문으로 분류·묶기 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.ljh.gameops.report.Translator translator;

    /** 묶기 둘째 단계(결정 41). 없으면 글자 비교만 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    DuplicateJudge judge;
    @org.springframework.beans.factory.annotation.Value("${gameops.dedupe.ai-min-confidence:0.5}")
    double aiMinConfidence = 0.5;
    /** 한 제보에 AI 에게 물어보는 이슈 수 상한(비용 상한 · 결정 42) */
    @org.springframework.beans.factory.annotation.Value("${gameops.dedupe.ai-candidates:3}")
    int aiCandidates = 3;

    /** 플레이어가 붙여 넣은 「게임진단」 줄에 오류가 있으면 게임 오류 기록에도 넣는다(결정 37). 실패해도 접수는 그대로 */
    private void recordDiagError(Game game, SubmitReport cmd) {
        if (clientErrors == null) return;
        var d = com.ljh.gameops.report.ReproInfo.diag(cmd.body());
        if (d == null || d.error() == null) return;
        try {
            clientErrors.record(game.getId(), new com.ljh.gameops.errors.ClientErrors.Incoming(d.error(), null, d.version(), d.device(), "제보 글의 게임진단"));
        } catch (RuntimeException e) {
            log.warn("게임진단 오류 기록 실패: {}", e.getMessage());
        }
    }

    /** 도배·몰림 막기(결정 36 · 43) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    IntakeGuard guard;

    public IntakeResult submit(SubmitReport cmd) {
        return submit(cmd, false);
    }

    /** 보류함에서 꺼낸 제보(HeldReleaser). 몰림 검사는 건너뛴다 */
    public IntakeResult submitReleased(SubmitReport cmd) {
        return submit(cmd, true);
    }

    /**
     * 봇이 꺼져 있던 동안 채널에 올라온 글을 나중에 받을 때(DiscordBackfill). 이미 채널에 올라와 있는 글이라 도배·몰림 검사는 하지 않고,
     * 받은 시각은 글을 쓴 시각으로 둔다(처리 지표가 늦게 받은 만큼 길어지지 않게)
     */
    public IntakeResult submitBackfill(SubmitReport cmd, OffsetDateTime sentAt) {
        IntakeResult r = submit(cmd, true, true);
        if (sentAt != null && r.outcome() != IntakeResult.Outcome.DUPLICATE_DELIVERY && r.reportId() > 0) {
            tx.executeWithoutResult(st -> em.createNativeQuery("UPDATE report SET received_at = ?1 WHERE id = ?2")
                    .setParameter(1, sentAt).setParameter(2, r.reportId()).executeUpdate());
        }
        return r;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    private IntakeResult submit(SubmitReport cmd, boolean released) {
        return submit(cmd, released, false);
    }

    private IntakeResult submit(SubmitReport cmd, boolean released, boolean skipGuard) {
        Game game = games.findByCode(cmd.gameCode())
                .orElseThrow(() -> new NotFoundException("등록되지 않은 게임: " + cmd.gameCode()));

        Optional<IntakeResult> redelivered = findRedelivery(cmd);
        if (redelivered.isPresent()) return redelivered.get();

        if (!skipGuard && guard != null && guard.check(game, cmd, released) == IntakeGuard.Decision.HOLD) return hold(game, cmd);

        // 외국어면 AI 가 한국어로 옮겨 운영자가 읽게 한다(결정 42). 분류는 원문 먼저, 묶기 글자 비교는 원문(번역문 비교는 평가에서 도움이 안 됨)
        String lang = com.ljh.gameops.report.Lang.detect(cmd.body());
        String ko = com.ljh.gameops.report.Lang.isKorean(lang) || translator == null || !translator.enabled() ? null
                : translator.toKorean(game.getCode(), cmd.body());
        Text text = new Text(cmd.body(), lang, ko);
        Classification c = classifyWithTranslation(classifier, game.getCode(), cmd.body(), ko);
        // AI 판정은 DB 거래 밖에서(응답 기다리는 동안 DB 연결을 잡지 않게). 결과는 「붙일 후보」 하나
        AiHint hint = aiHint(game, text, c);

        // 동시 제보가 같은 이슈를 두 번 만들거나(고유 인덱스), 같은 이슈를 동시에 고치면(version) 다시 시도한다.
        for (int attempt = 1; ; attempt++) {
            try {
                IntakeResult r = tx.execute(status -> store(game, cmd, c, hint, text));
                recordDiagError(game, cmd);
                return r;
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException
                     | PessimisticLockingFailureException e) {
                Optional<IntakeResult> again = findRedelivery(cmd);
                if (again.isPresent()) return again.get();
                if (attempt >= MAX_TRIES) throw e;
                log.info("제보 저장 경합, 다시 시도 {}/{}: {}", attempt, MAX_TRIES, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 플레이어가 자기 제보(또는 봇의 접수 답장)에 답글로 버전·기기·재현 순서·스크린샷을 더 적었을 때.
     * 새 제보로 받지 않고 원래 제보에 채운다. 원래 제보를 못 찾으면 빈 값(그때는 보통 제보로 받는다).
     * 답글 글 자체는 재현 순서가 비어 있을 때만 재현 순서로 쓴다.
     */
    @org.springframework.transaction.annotation.Transactional
    public Optional<Long> addRepro(com.ljh.gameops.report.SourceChannel channel, String originalExternalId,
                                   String text, String attachmentUrls) {
        if (originalExternalId == null) return Optional.empty();
        Optional<Report> orig = reports.findBySourceChannelAndExternalId(channel, originalExternalId);
        if (orig.isEmpty()) return Optional.empty();
        Report r = orig.get();
        var parsed = com.ljh.gameops.report.ReproInfo.parse(text);
        r.addRepro(new com.ljh.gameops.report.ReproInfo(parsed.version(), parsed.device(), text), attachmentUrls);
        reports.saveAndFlush(r);
        Optional<Long> issueId = links.findByReportId(r.getId()).map(IssueReport::getIssueId);
        // 슬랙 카드의 재현 정보 줄을 새로
        issueId.ifPresent(id -> events.publishEvent(com.ljh.gameops.notify.IssueChanged.counts(id)));
        return issueId;
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
    private Optional<Match> findRecurrence(Long gameId, String key, String normalized, Category category) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(props.recurrenceWindowDays());
        Match best = null;
        for (Issue i : issues.findByGameIdAndStatusAndResolvedAtAfter(gameId, IssueStatus.RESOLVED, since)) {
            double s = i.getNormalizedKey().equals(key) ? 1.0 : similarity(normalized, category, i);
            if (s >= props.duplicateThreshold() && (best == null || s > best.score()
                    || (s == best.score() && i.getResolvedAt().isAfter(best.issue().getResolvedAt())))) {
                best = new Match(i, s);
            }
        }
        // QA 확인 대기 중인 이슈와 같은 제보가 또 오면 「고쳤다는데 아직 난다」는 신호라 재발로 표시한다(결정 31)
        for (Issue i : issues.findByGameIdAndStatusIn(gameId, java.util.List.of(IssueStatus.VERIFYING))) {
            double s = i.getNormalizedKey().equals(key) ? 1.0 : similarity(normalized, category, i);
            if (s >= props.duplicateThreshold() && (best == null || s > best.score())) best = new Match(i, s);
        }
        return Optional.ofNullable(best);
    }

    /**
     * 외국어 제보 분류(결정 42): 원문을 먼저(규칙에 영어·일본어 낱말이 있다), 아무 규칙도 안 걸리면(기타·낮음) 번역으로.
     * 평가에서 번역만 쓰면 번역문이 규칙 낱말과 다르게 옮겨져 더 틀렸다. 제목은 번역이 있으면 번역으로(운영자가 읽게)
     */
    public static Classification classifyWithTranslation(IssueClassifier classifier, String game, String original, String ko) {
        Classification c = classifier.classify(game, original);
        if (ko == null) return c;
        if (c.category() == Category.OTHER && c.severity() == Severity.LOW) {
            Classification t = classifier.classify(game, ko);
            if (t.category() != Category.OTHER || t.severity() != Severity.LOW) c = t;
        }
        return new Classification(c.category(), c.severity(), com.ljh.gameops.classify.Titles.fromBody(ko), c.classifiedBy());
    }

    /** 몰림 모드: 실적 없는 사람 제보를 보류함에 받아 둔다(결정 44). 같은 메시지가 또 오면 한 번만 */
    private IntakeResult hold(Game game, SubmitReport cmd) {
        try {
            String payload = (mapper != null ? mapper : new com.fasterxml.jackson.databind.ObjectMapper()).writeValueAsString(cmd);
            tx.executeWithoutResult(st -> em.createNativeQuery("""
                    INSERT INTO held_report (game_id, author_hash, external_id, payload) VALUES (?1, ?2, ?3, ?4)
                    ON CONFLICT (external_id) WHERE external_id IS NOT NULL DO NOTHING""")
                    .setParameter(1, game.getId()).setParameter(2, hasher.hash(cmd.channel().name(), cmd.authorId()))
                    .setParameter(3, cmd.externalId()).setParameter(4, payload).executeUpdate());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        log.info("몰림 모드: 제보를 보류함에 받아 둠 game={}", game.getCode());
        return new IntakeResult(0, 0, IntakeResult.Outcome.HELD, 0);
    }

    /** 원문 · 언어 · 한국어 번역(없으면 null) */
    record Text(String original, String lang, String ko) {}

    /** AI 가 같은 문제라고 본 열린 이슈와 그 확신 */
    record AiHint(Long issueId, double confidence) {}

    /**
     * 글자 비교로 붙일 이슈가 없을 때만, 같은 분류의 열린 이슈 가운데 글자가 가장 비슷한 몇 개를 AI 에게 묻는다.
     * 글자 비교로 이미 붙거나 글이 똑같으면 부르지 않는다(비용 0). 판정 실패·예산 초과면 null → 새 이슈.
     */
    AiHint aiHint(Game game, Text text, Classification c) {
        if (judge == null || !judge.enabled()) return null;
        String body = text.original();
        String normalized = TextNormalizer.normalize(text.original());
        if (normalized.isEmpty()) return null;
        String key = TextNormalizer.key(normalized);
        if (issues.findByGameIdAndNormalizedKeyAndStatusIn(game.getId(), key, OPEN).isPresent()) return null;
        record Cand(IssueCandidate i, double score) {}
        List<Cand> cands = new ArrayList<>();
        var mine = Similarity.bigrams(normalized);
        for (IssueCandidate i : issues.candidates(game.getId(), OPEN)) {
            if (props.sameCategoryOnly() && i.category() != c.category() && i.autoCategory() != c.category()) continue;
            double s = Similarity.jaccard(mine, Similarity.bigrams(i.normalizedText()));
            if (s >= props.duplicateThreshold()) return null; // 글자 비교로 붙는다
            cands.add(new Cand(i, s));
        }
        cands.sort(java.util.Comparator.comparingDouble(Cand::score).reversed()
                .thenComparing(m -> m.i().id(), java.util.Comparator.reverseOrder()));
        for (Cand m : cands.subList(0, Math.min(aiCandidates, cands.size()))) {
            Issue issue = issues.findById(m.i().id()).orElse(null);
            if (issue == null) continue;
            String rep = reports.findById(issue.getRepresentativeReportId()).map(Report::getBody).orElse(issue.getNormalizedText());
            double conf = judge.confidence(game.getCode(), body, rep);
            if (conf >= aiMinConfidence) {
                log.info("AI 묶기 판정: 이슈 {} 와 같은 문제(확신 {})", issue.getId(), conf);
                return new AiHint(issue.getId(), conf);
            }
        }
        return null;
    }

    private IntakeResult store(Game game, SubmitReport cmd, Classification c, AiHint hint, Text text) {
        Report report = reports.saveAndFlush(new Report(game.getId(), cmd.channel(), cmd.externalId(),
                hasher.hash(cmd.channel().name(), cmd.authorId()), cmd.body(), cmd.attachmentUrls(), cmd.replyTo())
                .withRepro(com.ljh.gameops.report.ReproInfo.merge(cmd.repro(), com.ljh.gameops.report.ReproInfo.parse(cmd.body())))
                .withLanguage(text.lang(), text.ko()));

        String normalized = TextNormalizer.normalize(text.original());
        // 링크·이모지·감탄사만 있는 글은 정규화하면 빈 문자열이 된다. 빈 글끼리 묶으면 무관한 제보가 한 이슈로
        // 몰리므로, 빈 글은 비교하지 않고 제보마다 따로 이슈를 만든다.
        boolean empty = normalized.isEmpty();
        String key = TextNormalizer.key(empty ? "empty-report:" + report.getId() : normalized);

        Issue best = null;
        double bestScore = 0;
        boolean aiMatched = false;
        if (!empty) {
            Optional<Issue> exact = issues.findByGameIdAndNormalizedKeyAndStatusIn(game.getId(), key, OPEN);
            if (exact.isPresent()) {
                best = exact.get();
                bestScore = 1.0;
            } else {
                // 칸 네 개만 읽고, 새 글의 글자 묶음은 한 번만 만든다(열린 이슈 수만큼 도는 곳)
                var mine = Similarity.bigrams(normalized);
                Long bestId = null;
                for (IssueCandidate i : issues.candidates(game.getId(), OPEN)) {
                    if (props.sameCategoryOnly() && i.category() != c.category() && i.autoCategory() != c.category()) continue;
                    double s = Similarity.jaccard(mine, Similarity.bigrams(i.normalizedText()));
                    if (s > bestScore) { bestScore = s; bestId = i.id(); }
                }
                if (bestId != null) best = issues.findById(bestId).orElse(null);
            }
        }

        if (best != null && bestScore >= props.duplicateThreshold()) {
            // 이슈 한 줄을 잠그고 DB 최신 값으로 다시 읽는다. 동시에 붙는 제보들이 차례로 줄을 서고,
            // 그사이 다른 요청이 닫은 이슈에는 붙이지 않는다.
            em.refresh(best, LockModeType.PESSIMISTIC_WRITE);
            if (!best.getStatus().isOpen()) {
                best = null;
            } else if (!best.getNormalizedKey().equals(key)) {
                // 잠그기 전에 사람이 대표 제보나 분류를 바꿨을 수 있다. 최신 값으로 다시 잰다.
                bestScore = similarity(normalized, c.category(), best);
            }
        }
        if ((best == null || bestScore < props.duplicateThreshold()) && hint != null) {
            // 글자 비교로는 못 붙였지만 AI 가 같은 문제라고 본 이슈. 잠근 뒤에도 열려 있을 때만 붙인다
            Issue h = issues.findById(hint.issueId()).orElse(null);
            if (h != null) {
                em.refresh(h, LockModeType.PESSIMISTIC_WRITE);
                if (h.getStatus().isOpen()) { best = h; bestScore = hint.confidence(); aiMatched = true; }
            }
        }
        if (best != null && (bestScore >= props.duplicateThreshold() || aiMatched)) {
            var before = best.getSeverity();
            best.attachReport(c.severity());
            issues.saveAndFlush(best);
            links.save(new IssueReport(best.getId(), report.getId(), bestScore));
            if (best.getSeverity() != before) {
                outbox.enqueue(NotificationType.SEVERITY_RAISED, Map.of("issueId", best.getId(), "from", before.name()));
            }
            // 슬랙 카드의 제보 수를 새로(커밋된 뒤)
            events.publishEvent(com.ljh.gameops.notify.IssueChanged.counts(best.getId()));
            return new IntakeResult(report.getId(), best.getId(), IntakeResult.Outcome.ATTACHED_TO_EXISTING, bestScore);
        }

        Issue fresh = new Issue(game.getId(), c.title(), c.category(), c.severity(),
                key, normalized, report.getId(), c.classifiedBy());
        if (!empty) findRecurrence(game.getId(), key, normalized, c.category()).ifPresent(m -> fresh.markRecurrenceOf(m.issue().getId(), m.score()));
        Issue created = issues.saveAndFlush(fresh);
        links.save(new IssueReport(created.getId(), report.getId(), 1.0));
        Map<String, Object> payload = new HashMap<>();
        payload.put("issueId", created.getId());
        if (created.getRecurrenceOf() != null) payload.put("recurrenceOf", created.getRecurrenceOf());
        outbox.enqueue(NotificationType.NEW_ISSUE, payload);
        return new IntakeResult(report.getId(), created.getId(), IntakeResult.Outcome.NEW_ISSUE, 1.0);
    }

    /**
     * 이 글과 이슈가 얼마나 비슷한가. 분류가 다르면 0(같은 문제로 보지 않는다).
     * 이슈의 지금 분류(사람이 고친 값) 또는 처음 자동 분류 중 하나와 같으면 비교한다 — 사람이 「결제→불편」으로 고친 뒤에도
     * 같은 글은 자동으로 「결제」가 나오므로, 처음 분류를 함께 보지 않으면 고친 이슈에 비슷한 제보가 안 붙는다.
     * 글이 똑같은 경우(정규화 키 일치)는 이 함수를 거치지 않고 분류와 상관없이 묶는다.
     */
    private double similarity(String normalized, Category category, Issue issue) {
        if (props.sameCategoryOnly() && issue.getCategory() != category && issue.getAutoCategory() != category) return 0.0;
        return Similarity.jaccard(normalized, issue.getNormalizedText());
    }
}
