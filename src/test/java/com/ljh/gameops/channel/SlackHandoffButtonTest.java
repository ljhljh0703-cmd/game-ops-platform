// 슬랙 정리 카드의 「🤖 개발 AI에게 넘기기」 — 관리자만, 누르면 뒤에서 깃허브 이슈를 만들고 누른 카드가 「넘김 · 링크」로 바뀐다
package com.ljh.gameops.channel;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackSignatureVerifier;
import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.handoff.GitHubIssues;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Import({SlackInteractionTest.FakeWebConfig.class, SlackHandoffButtonTest.GitHubConfig.class})
class SlackHandoffButtonTest extends IntegrationTest {
    static final List<String> made = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class GitHubConfig {
        @Bean @Primary GitHubIssues fakeGitHubForButton() {
            return new GitHubIssues() {
                public Created create(String repo, String title, String body, List<String> labels) {
                    made.add(repo);
                    return new Created(7, "https://github.com/" + repo + "/issues/7");
                }
                public boolean ready() { return true; }
            };
        }
    }

    @Autowired SlackInteractionTest.FakeWeb web;
    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;
    @Autowired FeedbackService feedback;
    private final SlackSignatureVerifier signer = new SlackSignatureVerifier("test-signing-secret");

    @BeforeEach
    void reset() { made.clear(); web.digestUpdated.clear(); }

    private ResponseEntity<Map> press(String user, long digestId) {
        String json = "{\"type\":\"block_actions\",\"user\":{\"id\":\"" + user + "\"},\"container\":{\"channel_id\":\"C_JELLY\",\"message_ts\":\"111.222\"},"
                + "\"actions\":[{\"action_id\":\"digest_handoff\",\"value\":\"" + digestId + "\"}]}";
        String body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.set("X-Slack-Request-Timestamp", String.valueOf(ts));
        h.set("X-Slack-Signature", signer.sign(String.valueOf(ts), body));
        return http.exchange("/slack/interactions", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void 누르면_이슈를_만들고_누른_카드를_바꾼다() {
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        long d = feedback.post("jelly-panic", 7, "*정리*", "ai-agent");

        assertThat(press("U_ADMIN", d).getStatusCode()).isEqualTo(HttpStatus.OK);
        SlackInteractionTest.await(() -> !web.digestUpdated.isEmpty());

        assertThat(made).containsExactly("ljhljh0703-cmd/jelly-panic");
        var card = web.digestUpdated.get(0);
        assertThat(card.handoffs()).hasSize(1);
        assertThat(card.handoffs().get(0).actor()).isEqualTo("slack:U_ADMIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slack_message WHERE kind = 'DIGEST' AND ref_id = ?", Integer.class, d)).isEqualTo(1);
    }

    @Test
    void 관리자가_아니면_넘기지_않는다() {
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        long d = feedback.post("jelly-panic", 7, "*정리*", "ai-agent");
        press("U_SOMEONE", d);
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(made).isEmpty();
    }
}
