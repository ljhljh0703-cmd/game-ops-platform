// 슬랙 버튼·입력 창 — 서명 맞는 관리자만 바꾸고(서명 틀림·오래된 요청·비관리자는 막음), 처리하면 원래 카드를 그 자리에서 바꾼다
package com.ljh.gameops.channel;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.channel.slack.SlackSignatureVerifier;
import com.ljh.gameops.intake.IntakeResult;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.report.SourceChannel;
import com.ljh.gameops.channel.slack.SlackWebApi;
import com.ljh.gameops.correction.CorrectionRepository;
import com.ljh.gameops.notify.IssueCard;
import com.slack.api.model.view.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(SlackInteractionTest.FakeWebConfig.class)
class SlackInteractionTest extends IntegrationTest {
    /** 슬랙 Web API 대신 부른 내용을 받아 적는다 */
    static class FakeWeb implements SlackWebApi {
        final List<View> opened = new ArrayList<>();
        final List<String[]> updatedAt = new ArrayList<>();
        final List<IssueCard> updated = new ArrayList<>();
        public boolean openView(String triggerId, View view) { opened.add(view); return true; }
        public void updateCard(String channel, String ts, IssueCard card) { updatedAt.add(new String[]{channel, ts}); updated.add(card); }
    }

    @TestConfiguration
    static class FakeWebConfig {
        @Bean @Primary FakeWeb fakeWeb() { return new FakeWeb(); }
    }

    @Autowired FakeWeb web;
    @Autowired CorrectionRepository corrections;

    @BeforeEach
    void resetWeb() { web.opened.clear(); web.updated.clear(); web.updatedAt.clear(); }

    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;
    @Autowired IssueRepository issues;
    @Autowired AuditLogRepository audit;

    private final SlackSignatureVerifier signer = new SlackSignatureVerifier("test-signing-secret");

    private long issue() {
        IntakeResult r = intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a", "타워 설치가 안 돼요 버그", null));
        return r.issueId();
    }

    private String body(String user, String value) {
        String json = "{\"type\":\"block_actions\",\"user\":{\"id\":\"" + user + "\"},\"actions\":[{\"value\":\"" + value + "\"}]}";
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    private ResponseEntity<Map> post(String body, long ts, String signature) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.set("X-Slack-Request-Timestamp", String.valueOf(ts));
        h.set("X-Slack-Signature", signature);
        return http.exchange("/slack/interactions", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void 관리자가_해결_버튼을_누르면_상태가_바뀐다() {
        long id = issue();
        long ts = Instant.now().getEpochSecond();
        String b = body("U_ADMIN", id + ":RESOLVED");
        ResponseEntity<Map> r = post(b, ts, signer.sign(String.valueOf(ts), b));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.findById(id).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(audit.findByTargetOrderByIdAsc("issue:" + id).get(0).getActor()).isEqualTo("slack:U_ADMIN");
    }

    @Test
    void 슬랙식_인코딩도_서명이_맞는다() {
        // 실제 슬랙은 공백을 %20, 16진수를 소문자로 보낼 수 있다. 원래 바이트로 서명을 계산해야 통과한다.
        long id = issue();
        long ts = Instant.now().getEpochSecond();
        String json = "{\"type\":\"block_actions\",\"user\":{\"id\":\"U_ADMIN\"},\"actions\":[{\"value\":\"" + id + ":RESOLVED\",\"text\":\"해결 버튼\"}]}";
        String b = "payload=" + lowerHex(URLEncoder.encode(json, StandardCharsets.UTF_8).replace("+", "%20"));
        ResponseEntity<Map> r = post(b, ts, signer.sign(String.valueOf(ts), b));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.findById(id).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
    }

    private static String lowerHex(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                out.append('%').append(s.substring(i + 1, i + 3).toLowerCase());
                i += 2;
            } else out.append(c);
        }
        return out.toString();
    }

    @Test
    void 서명이_틀리면_401() {
        long id = issue();
        long ts = Instant.now().getEpochSecond();
        ResponseEntity<Map> r = post(body("U_ADMIN", id + ":RESOLVED"), ts, "v0=deadbeef");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(issues.findById(id).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN);
    }

    @Test
    void 본문을_바꿔치기하면_401() {
        long id = issue();
        long ts = Instant.now().getEpochSecond();
        String signed = body("U_ADMIN", id + ":IN_PROGRESS");
        ResponseEntity<Map> r = post(body("U_ADMIN", id + ":WONT_FIX"), ts, signer.sign(String.valueOf(ts), signed));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 오분_넘은_요청은_401() {
        long id = issue();
        long ts = Instant.now().minusSeconds(600).getEpochSecond();
        String b = body("U_ADMIN", id + ":RESOLVED");
        assertThat(post(b, ts, signer.sign(String.valueOf(ts), b)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 관리자가_아니면_바뀌지_않는다() {
        long id = issue();
        long ts = Instant.now().getEpochSecond();
        String b = body("U_SOMEONE", id + ":RESOLVED");
        ResponseEntity<Map> r = post(b, ts, signer.sign(String.valueOf(ts), b));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("text").toString()).contains("권한");
        assertThat(issues.findById(id).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(audit.count()).isZero();
    }

    // ── 카드 버튼(실제 슬랙처럼 카드 위치·action_id 가 함께 옴) ──

    private String action(String user, String actionId, String value) {
        String json = "{\"type\":\"block_actions\",\"user\":{\"id\":\"" + user + "\"},\"trigger_id\":\"T1\","
                + "\"container\":{\"type\":\"message\",\"channel_id\":\"C_CARD\",\"message_ts\":\"111.222\"},"
                + "\"actions\":[{\"action_id\":\"" + actionId + "\",\"value\":\"" + value + "\"}]}";
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    private String submission(String user, String callback, String meta, String values) {
        String json = "{\"type\":\"view_submission\",\"user\":{\"id\":\"" + user + "\"},\"view\":{\"callback_id\":\"" + callback
                + "\",\"private_metadata\":\"" + meta + "\",\"state\":{\"values\":" + values + "}}}";
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    private ResponseEntity<Map> signed(String b) {
        long ts = Instant.now().getEpochSecond();
        return post(b, ts, signer.sign(String.valueOf(ts), b));
    }

    @Test
    void 해결을_누르면_원래_카드가_해결됨_누가_언제로_바뀐다() {
        long id = issue();
        assertThat(signed(action("U_ADMIN", "issue_status_resolved", id + ":RESOLVED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.findById(id).orElseThrow().getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(web.updatedAt).hasSize(1);
        assertThat(web.updatedAt.get(0)).containsExactly("C_CARD", "111.222");
        IssueCard c = web.updated.get(0);
        assertThat(c.status()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(c.lastAction()).startsWith("✅ 해결됨 · <@U_ADMIN> · ");
    }

    @Test
    void 다시_열기() {
        long id = issue();
        signed(action("U_ADMIN", "issue_status_resolved", id + ":RESOLVED"));
        signed(action("U_ADMIN", "issue_status_open", id + ":OPEN"));
        assertThat(issues.findById(id).orElseThrow().getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(web.updated.get(1).lastAction()).startsWith("↩️ 다시 열림");
    }

    @Test
    void 분류_고치기는_창을_열고_저장하면_분류를_고치고_카드를_바꾼다() {
        long id = issue(); // 「타워 설치가 안 돼요 버그」 → 버그/보통
        signed(action("U_ADMIN", "issue_reclassify", String.valueOf(id)));
        assertThat(web.opened).hasSize(1);
        View v = web.opened.get(0);
        assertThat(v.getCallbackId()).isEqualTo("reclassify");
        assertThat(v.getPrivateMetadata()).isEqualTo(id + "|C_CARD|111.222");

        String values = "{\"category\":{\"v\":{\"selected_option\":{\"value\":\"UX\"}}},"
                + "\"severity\":{\"v\":{\"selected_option\":{\"value\":\"LOW\"}}},"
                + "\"reason\":{\"v\":{\"value\":\"설치 위치 안내 문제\"}}}";
        ResponseEntity<Map> r = signed(submission("U_ADMIN", "reclassify", v.getPrivateMetadata(), values));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNull(); // 빈 응답 = 창 닫기
        var issue = issues.findById(id).orElseThrow();
        assertThat(issue.getCategory().name()).isEqualTo("UX");
        assertThat(issue.getSeverity().name()).isEqualTo("LOW");
        assertThat(corrections.findAll()).singleElement()
                .satisfies(c -> assertThat(c.getActor()).isEqualTo("slack:U_ADMIN"));
        assertThat(web.updated).singleElement().satisfies(c ->
                assertThat(c.lastAction()).startsWith("🏷️ 분류 고침 버그/보통 → 불편/낮음 · <@U_ADMIN>"));
    }

    @Test
    void 떼어내기는_묶인_제보를_골라_새_이슈로_만들고_원래_카드를_바꾼다() {
        // 같은 분류(버그)라 묶이는 두 글
        var a = intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a", "보석 합성 버그 있어요 합성 버튼 눌러도 안 돼요", null));
        var b = intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "b", "보석 합성 버그 있어요 합성하면 보석이 두 개로 늘어나요", null));
        assertThat(b.issueId()).isEqualTo(a.issueId());

        signed(action("U_ADMIN", "issue_detach", String.valueOf(a.issueId())));
        View v = web.opened.get(0);
        assertThat(v.getCallbackId()).isEqualTo("detach");

        String values = "{\"report\":{\"v\":{\"selected_option\":{\"value\":\"" + b.reportId() + "\"}}},\"reason\":{\"v\":{\"value\":null}}}";
        assertThat(signed(submission("U_ADMIN", "detach", v.getPrivateMetadata(), values)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issues.findById(a.issueId()).orElseThrow().getReportCount()).isEqualTo(1);
        assertThat(issues.count()).isEqualTo(2);
        assertThat(web.updated).singleElement().satisfies(c -> {
            assertThat(c.reportCount()).isEqualTo(1);
            assertThat(c.lastAction()).startsWith("✂️ 제보 1건을 #");
        });
    }

    @Test
    void 비관리자가_창을_저장하면_창에_오류를_띄우고_바꾸지_않는다() {
        long id = issue();
        String values = "{\"category\":{\"v\":{\"selected_option\":{\"value\":\"UX\"}}},\"severity\":{\"v\":{\"selected_option\":{\"value\":\"LOW\"}}}}";
        ResponseEntity<Map> r = signed(submission("U_SOMEONE", "reclassify", id + "|C_CARD|111.222", values));
        assertThat(r.getBody()).containsEntry("response_action", "errors");
        assertThat(issues.findById(id).orElseThrow().getCategory().name()).isEqualTo("BUG");
        assertThat(web.updated).isEmpty();
    }

    @Test
    void 제보가_하나뿐인데_떼어내려_하면_창에_오류() {
        long id = issue();
        long reportId = jdbc.queryForObject("select report_id from issue_report where issue_id = ?", Long.class, id);
        String values = "{\"report\":{\"v\":{\"selected_option\":{\"value\":\"" + reportId + "\"}}}}";
        ResponseEntity<Map> r = signed(submission("U_ADMIN", "detach", id + "|C_CARD|111.222", values));
        assertThat(r.getBody()).containsEntry("response_action", "errors");
        assertThat(((Map<?, ?>) r.getBody().get("errors")).get("report").toString()).contains("하나뿐");
    }
}
