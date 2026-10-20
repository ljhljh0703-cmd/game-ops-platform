// 슬랙 명령 /피드백정리 — 서명·관리자 확인 → 「정리 중」 바로 답 → 뒤에서 보고서를 기록하고 슬랙 카드 대기열에.
// 게임은 적은 이름 → 친 채널 → 전체 순. AI 가 쓴 정리는 카드에 AI 가 정리했다고 남는다
package com.ljh.gameops.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackSignatureVerifier;
import com.ljh.gameops.feedback.DigestWriter;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.report.SourceChannel;
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

import static org.assertj.core.api.Assertions.assertThat;

@Import(SlackCommandTest.FakeAiConfig.class)
class SlackCommandTest extends IntegrationTest {
    @TestConfiguration
    static class FakeAiConfig {
        @Bean @Primary DigestWriter fakeDigestWriter(ObjectMapper mapper) {
            return new DigestWriter((sys, user) ->
                    "{\"headline\":\"보스 튕김이 몰렸어요\",\"frequent\":[],\"fresh\":[],\"first\":[],\"misclassified\":[]}", mapper);
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;

    private final SlackSignatureVerifier signer = new SlackSignatureVerifier("test-signing-secret");

    private String form(String user, String text, String channelName) {
        return "command=" + enc("/피드백정리") + "&text=" + enc(text) + "&user_id=" + user
                + "&channel_id=C_X&channel_name=" + enc(channelName) + "&response_url=" + enc("https://example.com/not-slack");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private ResponseEntity<Map> post(String body) {
        long ts = Instant.now().getEpochSecond();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.set("X-Slack-Request-Timestamp", String.valueOf(ts));
        h.set("X-Slack-Signature", signer.sign(String.valueOf(ts), body));
        return http.exchange("/slack/commands", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private List<Map<String, Object>> digests() {
        SlackInteractionTest.await(() -> jdbc.queryForObject("SELECT count(*) FROM feedback_digest", Integer.class) > 0);
        return jdbc.queryForList("SELECT d.text, d.actor, d.days, d.report_count, g.code FROM feedback_digest d LEFT JOIN game g ON g.id = d.game_id");
    }

    @Test
    void 게임_채널에서_치면_그_게임_보고서가_올라간다() {
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "b", "타워가 안 지어져요 버그", null));

        var r = post(form("U_ADMIN", "", "젤리패닉-알림"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("response_type", "ephemeral");
        assertThat(r.getBody().get("text").toString()).contains("젤리 패닉 최근 7일 제보를 정리하고 있어요");
        var d = digests();
        assertThat(d).hasSize(1);
        assertThat(d.get(0)).containsEntry("code", "jelly-panic").containsEntry("actor", "ai-agent").containsEntry("report_count", 1);
        assertThat(d.get(0).get("text").toString()).startsWith("*보스 튕김이 몰렸어요*").contains("🔥 *자주 나온 문제*");
    }

    @Test
    void 게임_이름과_기간을_적을_수_있고_틀리면_알려준다() {
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "b", "타워가 안 지어져요 버그", null));
        var r = post(form("U_ADMIN", "보석타워 14일", "random"));
        assertThat(r.getBody().get("text").toString()).contains("보석타워 최근 14일");
        assertThat(digests().get(0)).containsEntry("code", "gemtower").containsEntry("days", 14);

        assertThat(post(form("U_ADMIN", "없는게임", "random")).getBody().get("text").toString()).contains("「없는게임」 게임을 못 찾았어요");
        assertThat(post(form("U_ADMIN", "40", "random")).getBody().get("text").toString()).contains("1~31일");
    }

    @Test
    void 관리자가_아니거나_서명이_틀리면_막는다() {
        assertThat(post(form("U_SOMEONE", "", "random")).getBody().get("text").toString()).contains("운영 담당만");
        String body = form("U_ADMIN", "", "random");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.set("X-Slack-Request-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
        h.set("X-Slack-Signature", "v0=deadbeef");
        assertThat(http.exchange("/slack/commands", HttpMethod.POST, new HttpEntity<>(body, h), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback_digest", Integer.class)).isZero();
    }

    @Test
    void 관리_API로도_한_번에_정리한다() {
        intake.submit(new SubmitReport("movefu", SourceChannel.API, null, "c", "점프하면 벽에 끼어요", null));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", "test-agent-key");
        var r = http.exchange("/api/admin/digests/auto", HttpMethod.POST, new HttpEntity<>("{\"game\":\"movefu\",\"days\":3}", h), Map.class);
        assertThat(r.getBody()).containsEntry("posted", true).containsEntry("byAi", true).containsEntry("reportCount", 1);
        var none = http.exchange("/api/admin/digests/auto", HttpMethod.POST, new HttpEntity<>("{\"game\":\"gemtower\"}", h), Map.class);
        assertThat(none.getBody()).containsEntry("posted", false);
    }
}
