// 패치노트(결정 39) — 마지막 공지 뒤 고친 버그·합쳐진 변경으로 초안 → 슬랙 카드 → 사람이 공지 → 디스코드.
// 이미 알린 건 다음 초안에 다시 안 나오고, 공지한 글은 못 고치고, AI 키로는 공지 못 한다
package com.ljh.gameops.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackCards;
import com.ljh.gameops.common.ConflictException;
import com.ljh.gameops.handoff.GitHubRepoReader;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.notify.Announcer;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxDispatcher;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.report.SourceChannel;
import com.slack.api.model.block.ActionsBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PatchNoteTest.Fakes.class)
class PatchNoteTest extends IntegrationTest {
    static class FakeAnnouncer implements Announcer {
        final List<String> posted = new CopyOnWriteArrayList<>();
        public String announce(String channelId, String title, String text) {
            posted.add(channelId + "|" + title + "|" + text);
            return "m" + posted.size();
        }
    }

    static class FakeReader implements GitHubRepoReader {
        final List<Merged> merged = new CopyOnWriteArrayList<>();
        public List<Merged> recentlyMerged(String repo) { return repo.endsWith("uzmap-forge") ? merged : List.of(); }
        public List<Commit> commitsSince(String repo, OffsetDateTime since) { return List.of(); }
        public boolean comment(String repo, int number, String body) { return true; }
        public boolean ready() { return true; }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeAnnouncer fakeAnnouncer() { return new FakeAnnouncer(); }
        @Bean @Primary FakeReader fakePatchReader() { return new FakeReader(); }
    }

    @Autowired FakeAnnouncer discord;
    @Autowired FakeReader github;
    @Autowired PatchNoteService patches;
    @Autowired IntakeService intake;
    @Autowired IssueService issues;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void reset() { discord.posted.clear(); github.merged.clear(); }

    private long resolved(String game, String body, String... more) {
        long id = intake.submit(new SubmitReport(game, SourceChannel.API, null, "a" + body.hashCode(), body, null)).issueId();
        for (String m : more) intake.submit(new SubmitReport(game, SourceChannel.API, null, "b" + m.hashCode(), m, null));
        issues.changeStatus(id, IssueStatus.RESOLVED, "slack:UQA", "slack");
        return id;
    }

    @Test
    void 고친_버그와_합쳐진_변경으로_초안_사람이_공지하면_디스코드로() {
        resolved("gemtower", "보스 웨이브에서 튕겨요", "보스웨이브에서 튕겨요ㅠ");
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "c", "상점이 안 열려요 버그", null)); // 안 고침
        resolved("jelly-panic", "하트 충전이 안 돼요 버그"); // 다른 게임
        github.merged.add(new GitHubRepoReader.Merged(7, "모바일 전장 경계 표시", "", "godju", OffsetDateTime.now().minusHours(1), "u"));
        github.merged.add(new GitHubRepoReader.Merged(8, "chore: 린트 설정", "", "godju", OffsetDateTime.now().minusHours(1), "u"));

        var d = patches.draft("gemtower", 14, "slack:U");
        assertThat(d.fixed()).isEqualTo(1);
        assertThat(d.changes()).isEqualTo(2);
        var card = patches.card(d.id());
        assertThat(card.draft()).isTrue();
        assertThat(card.text()).contains("**🐞 고친 버그**", "튕겨요 문제를 고쳤어요 (제보 2건)", "**✨ 바뀐 점**", "모바일 전장 경계 표시",
                "#보석타워-버그-제보에 알려 주세요").doesNotContain("린트", "상점", "하트");
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.PATCH_NOTE)).hasSize(1);
        var slack = SlackCards.patchCard(card);
        assertThat(slack.getBlocks()).filteredOn(b -> b instanceof ActionsBlock).singleElement().asString()
                .contains("patch_publish", "디스코드에 공지", "patch_edit");

        patches.edit(d.id(), card.text() + "\n다음 주에 새 스테이지가 열려요.", "slack:U", null, null);
        patches.publish(d.id(), "slack:U", null, null);
        dispatcher.dispatchDue();

        assertThat(discord.posted).singleElement().asString()
                .startsWith("1561709173522178068|📝 보석타워 업데이트 노트").contains("새 스테이지");
        var done = patches.card(d.id());
        assertThat(done.status()).isEqualTo("PUBLISHED");
        assertThat(SlackCards.patchCard(done).getBlocks()).noneMatch(b -> b instanceof ActionsBlock);
        assertThat(jdbc.queryForObject("SELECT discord_message FROM patch_note WHERE id = ?", String.class, d.id())).isEqualTo("m1");

        // 공지한 글은 못 고치고 다시 공지되지 않는다
        assertThatThrownBy(() -> patches.edit(d.id(), "x", "slack:U", null, null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> patches.publish(d.id(), "slack:U", null, null)).isInstanceOf(ConflictException.class);

        // 다음 초안은 마지막 공지 뒤부터 — 이미 알린 버그는 다시 안 나온다
        assertThat(patches.draft("gemtower", 14, "slack:U").id()).isNull();
        resolved("gemtower", "결제했는데 보석이 안 들어와요");
        var next = patches.draft("gemtower", 14, "slack:U");
        assertThat(patches.card(next.id()).text()).contains("보석이 안 들어와요").doesNotContain("튕겨요");
    }

    @Test
    void 알릴_게_없으면_초안을_안_만든다() {
        assertThat(patches.draft("movefu", 14, "slack:U").id()).isNull();
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.PATCH_NOTE)).isEmpty();
    }

    @Test
    void AI_키로는_초안까지_공지는_사람만() {
        resolved("gemtower", "보스 웨이브에서 튕겨요");
        HttpHeaders agent = new HttpHeaders();
        agent.set("X-Api-Key", "test-agent-key");
        agent.setContentType(MediaType.APPLICATION_JSON);
        var r = http.exchange("/api/admin/patch-notes", HttpMethod.POST, new HttpEntity<>(Map.of("game", "gemtower"), agent), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = ((Number) r.getBody().get("id")).longValue();
        var pub = http.exchange("/api/admin/patch-notes/" + id + "/publish", HttpMethod.POST, new HttpEntity<>(agent), Map.class);
        assertThat(pub.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patches.card(id).draft()).isTrue();
    }

    @Test
    void AI_는_항목만_고르고_다듬는다_모르는_번호와_멘션은_버린다() {
        var fixed = List.of(new PatchNoteWriter.Fixed(9, "맵 경계선이 안 보임", "기타", 3));
        var changes = List.of(new PatchNoteWriter.Change(1, "refactor: 타워 클래스 정리"), new PatchNoteWriter.Change(2, "모바일 첫 적 표시"));
        var w = new PatchNoteWriter((sys, user) -> """
                {"fixed":[{"id":9,"text":"맵 경계가 잘 보이게 고쳤어요 @everyone"},{"id":99,"text":"없는 항목"}],
                 "changes":[{"n":2,"text":"모바일에서 첫 적이 바로 보여요"},{"n":7,"text":"없는 변경"}]}""", mapper);
        var r = w.write("보석타워", fixed, changes, "#보석타워-버그-제보");
        assertThat(r.byAi()).isTrue();
        assertThat(r.text()).contains("맵 경계가 잘 보이게 고쳤어요 ＠everyone (제보 3건)", "모바일에서 첫 적이 바로 보여요")
                .doesNotContain("없는", "@everyone", "타워 클래스");

        var broken = new PatchNoteWriter((sys, user) -> { throw new IllegalStateException("AI 꺼짐"); }, mapper).write("보석타워", fixed, changes, null);
        assertThat(broken.byAi()).isFalse();
        assertThat(broken.text()).contains("맵 경계선이 안 보임 문제를 고쳤어요 (제보 3건)", "모바일 첫 적 표시", "버그 제보 채널").doesNotContain("refactor");
    }
}
