// 슬랙 버튼 — 서명 맞는 관리자만 상태를 바꾸고, 서명 틀림·오래된 요청·비관리자는 막는다
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlackInteractionTest extends IntegrationTest {
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
}
