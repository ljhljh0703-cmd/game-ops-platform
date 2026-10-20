// 개발 AI에게 넘기기 — 정리 → 게임 저장소 깃허브 이슈. 게임마다 하나, 두 번 눌러도 하나, 슬랙 정리 카드가 「넘김 · 링크」로 바뀐다
package com.ljh.gameops.handoff;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackCards;
import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.notify.DigestCardFactory;
import com.ljh.gameops.report.SourceChannel;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Import(DevHandoffTest.FakeGitHubConfig.class)
class DevHandoffTest extends IntegrationTest {
    static class FakeGitHub implements GitHubIssues {
        final List<String[]> made = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        public Created create(String repo, String title, String body, List<String> labels) {
            if (fail) throw new IllegalStateException("토큰에 " + repo + " 이슈 쓰기 권한이 없어요");
            made.add(new String[]{repo, title, body, String.join(",", labels)});
            return new Created(made.size() + 40, "https://github.com/" + repo + "/issues/" + (made.size() + 40));
        }
        public boolean ready() { return true; }
    }

    @TestConfiguration
    static class FakeGitHubConfig {
        @Bean @Primary FakeGitHub fakeGitHub() { return new FakeGitHub(); }
    }

    @Autowired FakeGitHub github;
    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;
    @Autowired FeedbackService feedback;
    @Autowired DigestCardFactory digestCards;

    @BeforeEach
    void reset() { github.made.clear(); github.fail = false; }

    private HttpHeaders key() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", "test-agent-key");
        return h;
    }

    private ResponseEntity<List> handoff(long digestId) {
        return http.exchange("/api/admin/digests/" + digestId + "/handoff", HttpMethod.POST, new HttpEntity<>(key()), List.class);
    }

    @Test
    void 게임_정리는_그_게임_저장소에_이슈_하나_두_번_눌러도_하나() {
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        long d = feedback.post("jelly-panic", 7, "*보스 튕김이 몰렸어요*\n🔥 *자주 나온 문제*", "ai-agent");

        var r = handoff(d);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) r.getBody().get(0)).containsEntry("repo", "ljhljh0703-cmd/jelly-panic")
                .containsEntry("already", false).containsEntry("issueCount", 1);
        assertThat(github.made).hasSize(1);
        assertThat(github.made.get(0)[1]).isEqualTo("[플레이어 피드백] 젤리 패닉 · 고칠 것 1건 (최근 7일)");
        assertThat(github.made.get(0)[2]).contains("보스 튕김이 몰렸어요", "보스 웨이브에서 튕겨요");
        assertThat(github.made.get(0)[3]).isEqualTo("player-feedback");

        var again = handoff(d);
        assertThat((Map<String, Object>) again.getBody().get(0)).containsEntry("already", true);
        assertThat(github.made).hasSize(1);

        // 카드: 버튼 대신 넘긴 링크와 누가
        var card = SlackCards.digestCard(digestCards.current(d));
        assertThat(card.getBlocks()).hasSize(5).noneMatch(b -> b instanceof ActionsBlock);
        var last = (ContextBlock) card.getBlocks().get(4);
        assertThat(last.getElements().get(0).toString()).contains("개발 AI에게 넘김", "https://github.com/ljhljh0703-cmd/jelly-panic/issues/41|젤리 패닉 jelly-panic#41", "AI 에이전트");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DEV_HANDOFF'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 넘기기_전_카드에는_버튼() {
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a", "타워가 안 지어져요 버그", null));
        long d = feedback.post("gemtower", 7, "*정리*", "ai-agent");
        var card = SlackCards.digestCard(digestCards.current(d));
        assertThat(card.getBlocks()).hasSize(5);
        var actions = (ActionsBlock) card.getBlocks().get(4);
        assertThat(actions.getElements().get(0).toString()).contains("digest_handoff", "개발 AI에게 넘기기", "value=" + d);
    }

    @Test
    void 전체_정리는_제보가_있는_게임마다_하나씩() {
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        intake.submit(new SubmitReport("movefu", SourceChannel.API, null, "b", "점프하면 벽에 끼어요", null));
        long d = feedback.post(null, 7, "*전체 정리*", "ai-agent");
        var r = handoff(d);
        assertThat(r.getBody()).hasSize(2);
        assertThat(github.made).extracting(m -> m[0]).containsExactlyInAnyOrder("ljhljh0703-cmd/jelly-panic", "ljhljh0703-cmd/few-moves");
    }

    @Test
    void 깃허브가_안_되면_이유를_알려주고_기록을_남기지_않는다() {
        github.fail = true;
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        long d = feedback.post("jelly-panic", 7, "*정리*", "ai-agent");
        var r = http.exchange("/api/admin/digests/" + d + "/handoff", HttpMethod.POST, new HttpEntity<>(key()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("error").toString()).contains("이슈 쓰기 권한이 없어요");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_handoff", Integer.class)).isZero();
    }
}
