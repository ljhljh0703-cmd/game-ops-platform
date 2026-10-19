// REST API — 제보 등록, 이슈 조회, 관리자만 상태 변경, 감사 기록
package com.ljh.gameops.intake;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.audit.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTest extends IntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired AuditLogRepository audit;

    private long createIssue() {
        ResponseEntity<Map> r = http.postForEntity("/api/reports",
                Map.of("game", "jelly-panic", "authorId", "web-1", "body", "젤리가 화면 밖으로 사라지는 버그"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) r.getBody().get("issueId")).longValue();
    }

    private ResponseEntity<Map> patchStatus(long id, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.set("X-Api-Key", key);
        return http.exchange("/api/issues/" + id + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "RESOLVED"), h), Map.class);
    }

    @Test
    void 헬스체크는_UP() {
        ResponseEntity<Map> r = http.getForEntity("/actuator/health", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void 제보를_올리면_이슈_목록에_보인다() {
        long id = createIssue();
        ResponseEntity<List> list = http.getForEntity("/api/issues?game=jelly-panic", List.class);
        assertThat(list.getBody()).hasSize(1);
        ResponseEntity<Map> detail = http.getForEntity("/api/issues/" + id, Map.class);
        assertThat((List<?>) detail.getBody().get("reportIds")).hasSize(1);
    }

    @Test
    void 없는_게임은_404() {
        ResponseEntity<Map> r = http.postForEntity("/api/reports",
                Map.of("game", "no-such-game", "authorId", "a", "body", "x"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 빈_제보는_400() {
        ResponseEntity<Map> r = http.postForEntity("/api/reports",
                Map.of("game", "gemtower", "authorId", "a", "body", " "), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 외부_ID가_너무_길면_400() {
        ResponseEntity<Map> r = http.postForEntity("/api/reports",
                Map.of("game", "gemtower", "authorId", "a", "body", "버그", "externalId", "x".repeat(101)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 같은_내용의_열린_이슈가_있으면_닫힌_이슈를_다시_열_수_없다_409() {
        long first = createIssue();
        patchStatus(first, "test-admin-key"); // RESOLVED
        long second = createIssue();          // 같은 내용 → 새 이슈
        assertThat(second).isNotEqualTo(first);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", "test-admin-key");
        ResponseEntity<Map> r = http.exchange("/api/issues/" + first + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "OPEN"), h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 관리자_키_없이는_상태를_못_바꾼다() {
        long id = createIssue();
        assertThat(patchStatus(id, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patchStatus(id, "wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(audit.count()).isZero();
    }

    @Test
    void 관리자는_상태를_바꾸고_감사_기록이_남는다() {
        long id = createIssue();
        ResponseEntity<Map> r = patchStatus(id, "test-admin-key");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("RESOLVED");
        var logs = audit.findByTargetOrderByIdAsc("issue:" + id);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActor()).isEqualTo("local-admin");
        assertThat(logs.get(0).getDetail()).contains("OPEN->RESOLVED");
    }
}
