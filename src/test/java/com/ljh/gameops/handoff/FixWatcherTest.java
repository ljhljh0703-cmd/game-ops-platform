// 고친 코드가 들어오면 자동 해결 — 합쳐진 PR·기본 브랜치 커밋의 ops-번호 중 그 게임의 열린 이슈만 해결, 한 번만, 합친 사람이 기록에
package com.ljh.gameops.handoff;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Import(FixWatcherTest.FakeReaderConfig.class)
class FixWatcherTest extends IntegrationTest {
    static class FakeReader implements GitHubRepoReader {
        final List<Merged> merged = new CopyOnWriteArrayList<>();
        final List<Commit> commits = new CopyOnWriteArrayList<>();
        final List<String> comments = new CopyOnWriteArrayList<>();
        public List<Merged> recentlyMerged(String repo) { return repo.endsWith("uzmap-forge") ? merged : List.of(); }
        public List<Commit> commitsSince(String repo, OffsetDateTime since) { return repo.endsWith("uzmap-forge") ? commits : List.of(); }
        public boolean comment(String repo, int number, String body) { comments.add(repo + "#" + number + " " + body); return true; }
        public boolean ready() { return true; }
    }

    @TestConfiguration
    static class FakeReaderConfig {
        @Bean @Primary FakeReader fakeReader() { return new FakeReader(); }
    }

    @Autowired FakeReader github;
    @Autowired FixWatcher watcher;
    @Autowired GameCatalog games;
    @Autowired IntakeService intake;
    @Autowired IssueRepository issues;
    @Autowired OutboxRepository outbox;

    @BeforeEach
    void reset() { github.merged.clear(); github.commits.clear(); github.comments.clear(); }

    private long issue(String game, String body) {
        return intake.submit(new SubmitReport(game, SourceChannel.API, null, "a" + body.hashCode(), body, null)).issueId();
    }

    @Test
    void 합쳐진_PR의_ops_번호를_해결하고_플레이어_알림을_보낸다() {
        long gem = issue("gemtower", "보스 웨이브에서 튕겨요");
        long gem2 = issue("gemtower", "결제했는데 보석이 안 들어와요");
        long jelly = issue("jelly-panic", "하트 충전이 안 돼요 버그");
        github.merged.add(new GitHubRepoReader.Merged(3, "fix: 보스 웨이브 튕김 (ops-" + gem + ")",
                "고친 운영 이슈: ops-" + gem + ", ops-" + jelly + ", ops-99999\n재현 못 한 것: 결제 문제", "godju", OffsetDateTime.now().minusMinutes(1), "u"));

        var done = watcher.scan(games.byCode("gemtower").orElseThrow());

        assertThat(done).containsExactly(gem);
        assertThat(issues.findById(gem).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(issues.findById(gem2).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(issues.findById(jelly).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN); // 다른 게임 번호는 안 건드림
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.PLAYER_RESOLVED)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_log WHERE action = 'ISSUE_STATUS' ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("github:godju");
        assertThat(github.comments).hasSize(1).first().asString().contains("uzmap-forge#3", "ops-" + gem);

        // 다시 봐도 두 번 처리하지 않는다
        assertThat(watcher.scan(games.byCode("gemtower").orElseThrow())).isEmpty();
        assertThat(github.comments).hasSize(1);
    }

    @Test
    void 기본_브랜치에_바로_넣은_커밋도_해결하고_오래된_PR은_안_본다() {
        long gem = issue("gemtower", "타워가 안 지어져요 버그");
        long old = issue("gemtower", "랭킹이 안 떠요 버그");
        github.commits.add(new GitHubRepoReader.Commit("abc1234def", "fix: 타워 설치 (OPS-" + gem + ")", "codex-bot", OffsetDateTime.now(), "u"));
        github.merged.add(new GitHubRepoReader.Merged(1, "ops-" + old, "", "godju", OffsetDateTime.now().minusDays(3), "u"));

        assertThat(watcher.scan(games.byCode("gemtower").orElseThrow())).containsExactly(gem);
        assertThat(issues.findById(old).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN);
    }

    @Test
    void 번호_읽기() {
        assertThat(FixWatcher.refs("fix (ops-12), OPS 7, ops#3, tops-5, ops-12345678, myops-9")).containsExactly(12L, 7L, 3L);
    }
}
