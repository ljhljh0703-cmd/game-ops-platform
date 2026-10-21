// 개발 AI에게 넘기기(결정 29) — 피드백 정리 하나를 게임 저장소의 깃허브 이슈(개발 지시서)로 만든다.
// 게임을 정한 정리는 그 게임 저장소 하나, 전체 게임 정리는 제보가 있는 게임마다 하나씩. 같은 정리·같은 게임은 한 번만.
// 게임 코드는 자동으로 고치지 않는다 — 지시서를 받은 개발 AI는 사람이 돌린다(작가 결정 2026-10-21)
package com.ljh.gameops.handoff;

import com.ljh.gameops.audit.AuditLog;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.notify.DigestCard;
import com.ljh.gameops.notify.DigestChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DevHandoffService {
    static final int MAX_ITEMS = 300;

    private final FeedbackService feedback;
    private final GameCatalog games;
    private final GameRepository gameRepo;
    private final GitHubIssues github;
    private final JdbcTemplate jdbc;
    private final AuditLogRepository audit;
    private final ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.ljh.gameops.errors.ClientErrors clientErrors;

    public DevHandoffService(FeedbackService feedback, GameCatalog games, GameRepository gameRepo, GitHubIssues github,
                             JdbcTemplate jdbc, AuditLogRepository audit, ApplicationEventPublisher events) {
        this.feedback = feedback;
        this.games = games;
        this.gameRepo = gameRepo;
        this.github = github;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /** already = 전에 넘긴 것(새로 안 만듦) */
    public record Result(String game, String gameName, String repo, int number, String url, int issueCount, boolean already) {}

    /** 게임마다 넘긴 결과. 넘길 게 하나도 없으면 빈 목록. 저장소·토큰 문제는 예외(이유 담아) */
    public synchronized List<Result> handoff(long digestId, String actor, String channel, String ts) {
        FeedbackService.Digest d = feedback.get(digestId);
        List<GameSpec> targets = d.gameCode() != null
                ? games.byCode(d.gameCode()).map(List::of).orElse(List.of())
                : games.all();
        List<Result> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (GameSpec g : targets) {
            Optional<Result> done = existing(digestId, g);
            if (done.isPresent()) {
                out.add(done.get());
                continue;
            }
            List<FeedbackService.Item> items = feedback.recent(g.code(), d.days(), MAX_ITEMS);
            HandoffBrief.Brief brief = HandoffBrief.build(g.name(), g.code(), digestId, d.days(), d.gameCode() == null ? null : d.text(), items,
                    id -> clientErrors == null ? List.of() : clientErrors.forIssue(id, 3));
            if (brief == null) continue;
            if (g.repo() == null) {
                skipped.add(g.name());
                continue;
            }
            GitHubIssues.Created c = github.create(g.repo(), brief.title(), brief.body(), List.of(HandoffBrief.LABEL));
            long gameId = gameRepo.findByCode(g.code()).orElseThrow().getId();
            jdbc.update("""
                    INSERT INTO dev_handoff (digest_id, game_id, repo, issue_number, issue_url, issue_count, actor)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, digestId, gameId, g.repo(), c.number(), c.url(), brief.issueCount(), actor);
            audit.save(new AuditLog(actor, "DEV_HANDOFF", "digest:" + digestId,
                    g.code() + " → " + g.repo() + "#" + c.number() + " (고칠 것 " + brief.issueCount() + "건)"));
            out.add(new Result(g.code(), g.name(), g.repo(), c.number(), c.url(), brief.issueCount(), false));
        }
        if (out.isEmpty() && !skipped.isEmpty()) {
            throw new IllegalStateException(String.join("·", skipped) + " 저장소가 games.json 에 없어요(repo)");
        }
        if (out.stream().anyMatch(r -> !r.already()) || channel != null) events.publishEvent(new DigestChanged(digestId, channel, ts));
        return out;
    }

    /** 카드에 그릴 넘긴 기록 */
    public List<DigestCard.Handoff> handoffs(long digestId) {
        return jdbc.query("""
                SELECT h.issue_url, g.name, h.repo, h.issue_number, h.actor, h.created_at
                FROM dev_handoff h JOIN game g ON g.id = h.game_id WHERE h.digest_id = ? ORDER BY h.id
                """, (rs, i) -> new DigestCard.Handoff(rs.getString(1), rs.getString(2) + " " + shortRepo(rs.getString(3)) + "#" + rs.getInt(4),
                rs.getString(5), rs.getObject(6, OffsetDateTime.class)), digestId);
    }

    public boolean ready() { return github.ready(); }

    private Optional<Result> existing(long digestId, GameSpec g) {
        return jdbc.query("""
                SELECT h.repo, h.issue_number, h.issue_url, h.issue_count FROM dev_handoff h JOIN game ga ON ga.id = h.game_id
                WHERE h.digest_id = ? AND ga.code = ?
                """, (rs, i) -> new Result(g.code(), g.name(), rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4), true),
                digestId, g.code()).stream().findFirst();
    }

    private static String shortRepo(String repo) {
        int i = repo == null ? -1 : repo.indexOf('/');
        return i < 0 ? String.valueOf(repo) : repo.substring(i + 1);
    }
}
