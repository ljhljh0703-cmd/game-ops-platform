// 고친 코드가 들어오면 자동 해결(결정 30) — 게임 저장소를 20초마다 보고, 합쳐진 PR·기본 브랜치 커밋에 적힌 운영 번호(ops-12)를
// 그 게임의 열린 이슈면 「해결」로 바꾼다. 해결되면 늘 하던 대로 슬랙 카드가 초록이 되고 디스코드 제보자에게 「고쳤어요」가 간다.
// PR 을 합치거나 기본 브랜치에 넣는 것이 사람의 확인이라, 사람이 슬랙 [해결]을 따로 누르지 않는다. 누가 합쳤는지 기록에 남는다.
// 웹훅 대신 읽기만 하는 이유: 임시 공개 주소가 켤 때마다 바뀌고, 토큰에 웹훅 권한을 더 줄 필요가 없다. 공개 저장소는 읽기만으로 된다
package com.ljh.gameops.handoff;

import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.issue.Issue;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FixWatcher {
    private static final Logger log = LoggerFactory.getLogger(FixWatcher.class);
    /** ops-12 · ops 12 · ops#12 · OPS-12 */
    static final Pattern REF = Pattern.compile("(?i)(?<![\\w-])ops[-_ ]?#?(\\d{1,7})(?!\\d)");
    static final int LOOKBACK_HOURS = 24;

    private final GitHubRepoReader github;
    private final GameCatalog games;
    private final GameRepository gameRepo;
    private final IssueRepository issueRepo;
    private final IssueService issues;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public FixWatcher(GitHubRepoReader github, GameCatalog games, GameRepository gameRepo, IssueRepository issueRepo,
                      IssueService issues, JdbcTemplate jdbc, Clock clock) {
        this.github = github;
        this.games = games;
        this.gameRepo = gameRepo;
        this.issueRepo = issueRepo;
        this.issues = issues;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    static Set<Long> refs(String text) {
        Set<Long> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = REF.matcher(text);
        while (m.find()) out.add(Long.parseLong(m.group(1)));
        return out;
    }

    @Scheduled(initialDelayString = "${gameops.github.watch-ms:20000}", fixedDelayString = "${gameops.github.watch-ms:20000}")
    public void tick() {
        if (!github.ready()) return;
        for (GameSpec g : games.all()) {
            if (g.repo() == null) continue;
            try {
                scan(g);
            } catch (RuntimeException e) {
                log.debug("깃허브 보기 실패 {}: {}", g.repo(), e.getMessage());
            }
        }
    }

    /** 한 게임 저장소 한 번 보기. 이번에 해결한 운영 이슈 번호 */
    public List<Long> scan(GameSpec g) {
        Long gameId = gameRepo.findByCode(g.code()).map(x -> x.getId()).orElse(null);
        if (gameId == null) return List.of();
        OffsetDateTime since = OffsetDateTime.now(clock).minusHours(LOOKBACK_HOURS);
        List<Long> done = new ArrayList<>();
        for (GitHubRepoReader.Merged pr : github.recentlyMerged(g.repo())) {
            if (pr.mergedAt() == null || pr.mergedAt().isBefore(since)) continue;
            Set<Long> ids = refs(pr.title() + "\n" + pr.body());
            if (ids.isEmpty() || !claim(g.repo(), "PR", String.valueOf(pr.number()), pr.mergedBy())) continue;
            List<Long> r = resolve(g, gameId, ids, "github:" + pr.mergedBy(), "github-pr#" + pr.number());
            record(g.repo(), "PR", String.valueOf(pr.number()), r);
            done.addAll(r);
            if (!r.isEmpty()) {
                github.comment(g.repo(), pr.number(), g.qaCheck()
                        ? "🤖 게임 운영 플랫폼: 운영 이슈 " + join(r) + " 를 「QA 확인 대기」로 바꿨어요. QA가 슬랙에서 확인하면 해결로 바뀌고 제보한 플레이어에게 「고쳤어요」 알림이 갑니다."
                        : "🤖 게임 운영 플랫폼: 운영 이슈 " + join(r) + " 를 해결로 바꿨어요. 디스코드에 제보한 플레이어에게 「고쳤어요」 알림이 갑니다.");
            }
        }
        for (GitHubRepoReader.Commit c : github.commitsSince(g.repo(), since)) {
            Set<Long> ids = refs(c.message());
            if (ids.isEmpty() || !claim(g.repo(), "COMMIT", c.sha(), c.author())) continue;
            List<Long> r = resolve(g, gameId, ids, "github:" + c.author(), "github-commit:" + c.sha().substring(0, Math.min(7, c.sha().length())));
            record(g.repo(), "COMMIT", c.sha(), r);
            done.addAll(r);
        }
        if (!done.isEmpty()) log.info("고친 코드가 들어와 해결: {} → {}", g.repo(), done);
        return done;
    }

    /** 그 게임의 열린 이슈만 해결(QA 확인을 쓰는 게임이면 「QA 확인 대기」). 다른 게임 번호·없는 번호·이미 닫힌 것은 넘긴다 */
    private List<Long> resolve(GameSpec g, long gameId, Set<Long> ids, String actor, String via) {
        IssueStatus target = g.qaCheck() ? IssueStatus.VERIFYING : IssueStatus.RESOLVED;
        List<Long> out = new ArrayList<>();
        for (Long id : ids) {
            Optional<Issue> i = issueRepo.findById(id);
            if (i.isEmpty() || !Objects.equals(i.get().getGameId(), gameId) || !i.get().getStatus().isOpen()) continue;
            issues.changeStatus(id, target, actor, via);
            out.add(id);
        }
        return out;
    }

    /** 처음 보는 PR·커밋이면 true(이 서버가 맡음). 두 번 처리하지 않는다 */
    private boolean claim(String repo, String kind, String ref, String actor) {
        return jdbc.update("INSERT INTO merged_change (repo, kind, ref, actor) VALUES (?, ?, ?, ?) ON CONFLICT (repo, kind, ref) DO NOTHING",
                repo, kind, ref, actor) == 1;
    }

    private void record(String repo, String kind, String ref, List<Long> resolved) {
        jdbc.update("UPDATE merged_change SET resolved_issues = ? WHERE repo = ? AND kind = ? AND ref = ?",
                join(resolved), repo, kind, ref);
    }

    private static String join(List<Long> ids) {
        StringJoiner j = new StringJoiner(", ");
        for (Long id : ids) j.add("ops-" + id);
        return j.toString();
    }
}
