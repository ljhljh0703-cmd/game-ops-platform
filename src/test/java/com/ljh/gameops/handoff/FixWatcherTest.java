// 고친 코드가 들어오면 자동 해결 — 합쳐진 PR·기본 브랜치 커밋의 ops-번호 중 그 게임의 열린 이슈만 해결, 한 번만, 합친 사람이 기록에
package com.ljh.gameops.handoff;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
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
    @Autowired com.ljh.gameops.issue.IssueService issueService;

    @BeforeEach
    void reset() { github.merged.clear(); github.commits.clear(); github.comments.clear(); }

    /** QA 확인을 끈 보석타워(합치면 바로 해결). 운영 설정은 QA 확인을 켜 둔다 */
    private GameSpec noQa() {
        GameSpec g = games.byCode("gemtower").orElseThrow();
        return new GameSpec(g.code(), g.name(), g.discordChannels(), g.slackChannel(), g.slackUrgentChannel(), g.botName(), g.icon(),
                g.repo(), false, null);
    }

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

        var done = watcher.scan(noQa());

        assertThat(done).containsExactly(gem);
        assertThat(issues.findById(gem).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(issues.findById(gem2).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(issues.findById(jelly).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN); // 다른 게임 번호는 안 건드림
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.PLAYER_RESOLVED)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_log WHERE action = 'ISSUE_STATUS' ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("github:godju");
        assertThat(github.comments).hasSize(1).first().asString().contains("uzmap-forge#3", "ops-" + gem);

        // 다시 봐도 두 번 처리하지 않는다
        assertThat(watcher.scan(noQa())).isEmpty();
        assertThat(github.comments).hasSize(1);
    }

    @Test
    void 기본_브랜치에_바로_넣은_커밋도_해결하고_오래된_PR은_안_본다() {
        long gem = issue("gemtower", "타워가 안 지어져요 버그");
        long old = issue("gemtower", "랭킹이 안 떠요 버그");
        github.commits.add(new GitHubRepoReader.Commit("abc1234def", "fix: 타워 설치 (OPS-" + gem + ")", "codex-bot", OffsetDateTime.now(), "u"));
        github.merged.add(new GitHubRepoReader.Merged(1, "ops-" + old, "", "godju", OffsetDateTime.now().minusDays(3), "u"));

        assertThat(watcher.scan(noQa())).containsExactly(gem);
        assertThat(issues.findById(old).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN);
    }

    @Test
    void QA_확인을_쓰는_게임은_합쳐도_확인_대기_QA가_확인하면_그때_플레이어_답장() {
        GameSpec gem = games.byCode("gemtower").orElseThrow();
        assertThat(gem.qaCheck()).isTrue();
        long id = issue("gemtower", "보스 웨이브에서 튕겨요");
        github.merged.add(new GitHubRepoReader.Merged(5, "fix: 보스 튕김 ops-" + id, "", "godju", OffsetDateTime.now(), "u"));

        assertThat(watcher.scan(gem)).containsExactly(id);
        assertThat(issues.findById(id).orElseThrow().getStatus()).isEqualTo(IssueStatus.VERIFYING);
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.PLAYER_RESOLVED)).isEmpty(); // 아직 「고쳤어요」 안 감
        assertThat(github.comments).first().asString().contains("QA 확인 대기");

        // QA가 슬랙에서 확인 → 해결, 이때 플레이어 답장
        issueService.changeStatus(id, IssueStatus.RESOLVED, "slack:UQA", "slack");
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.PLAYER_RESOLVED)).hasSize(1);
    }

    @Test
    void 확인_대기_중에_같은_제보가_또_오면_재발로_표시한다() {
        long id = issue("gemtower", "보스 웨이브에서 튕겨요");
        github.merged.add(new GitHubRepoReader.Merged(6, "ops-" + id, "", "godju", OffsetDateTime.now(), "u"));
        watcher.scan(games.byCode("gemtower").orElseThrow());

        long again = intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "b", "보스 웨이브에서 또 튕겨요", null)).issueId();
        assertThat(again).isNotEqualTo(id);
        assertThat(issues.findById(again).orElseThrow().getRecurrenceOf()).isEqualTo(id);
    }

    @Test
    void 번호_읽기() {
        assertThat(FixWatcher.refs("fix (ops-12), OPS 7, ops#3, tops-5, ops-12345678, myops-9")).containsExactly(12L, 7L, 3L);
    }
}
