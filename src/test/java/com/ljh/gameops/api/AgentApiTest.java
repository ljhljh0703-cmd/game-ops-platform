// 에이전트가 쓰는 API — 게임 목록, 주간 보고 미리보기, 제보 본문(관리자만), 에이전트 키로 바꾸면 감사 기록에 ai-agent
package com.ljh.gameops.api;

import com.ljh.gameops.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentApiTest extends IntegrationTest {
    @Autowired TestRestTemplate http;

    private long report(String game, String body) {
        ResponseEntity<Map> r = http.postForEntity("/api/reports", Map.of("game", game, "authorId", "w", "body", body), Map.class);
        return ((Number) r.getBody().get("issueId")).longValue();
    }

    private HttpEntity<?> withKey(String key, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.set("X-Api-Key", key);
        return new HttpEntity<>(body, h);
    }

    @Test
    void 게임_목록에_미해결_이슈_수가_나온다() {
        report("movefu", "점프하면 캐릭터가 벽에 끼어요");
        ResponseEntity<List<Map<String, Object>>> r = http.exchange("/api/games", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertThat(r.getBody()).extracting(m -> m.get("code")).containsExactly("jelly-panic", "gemtower", "movefu");
        assertThat(r.getBody()).filteredOn(m -> "movefu".equals(m.get("code")))
                .extracting(m -> ((Number) m.get("openIssues")).longValue()).containsExactly(1L);
    }

    @Test
    void 주간_보고_미리보기는_보내지_않는다() {
        report("gemtower", "보석이 안 터져요 버그");
        ResponseEntity<Map> r = http.getForEntity("/api/games/gemtower/weekly-report?days=7", Map.class);
        assertThat((String) r.getBody().get("text")).startsWith("[보석타워] 주간 운영 보고").contains("신규 이슈 1건");
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where type = 'WEEKLY_REPORT'", Long.class)).isZero();
        assertThat(http.getForEntity("/api/games/gemtower/weekly-report?days=99", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 제보_본문은_키가_있어야_보인다() {
        long id = report("jelly-panic", "젤리가 화면 밖으로 사라져요");
        assertThat(http.exchange("/api/issues/" + id + "/reports", HttpMethod.GET, withKey(null, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<List> ok = http.exchange("/api/issues/" + id + "/reports", HttpMethod.GET, withKey("test-agent-key", null), List.class);
        assertThat(ok.getBody()).hasSize(1);
        assertThat((Map<String, Object>) ok.getBody().get(0)).containsEntry("body", "젤리가 화면 밖으로 사라져요")
                .doesNotContainKey("authorHash");
        assertThat(http.exchange("/api/issues/999/reports", HttpMethod.GET, withKey("test-agent-key", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 에이전트_키로_바꾸면_감사_기록에_ai_agent() {
        long id = report("jelly-panic", "결제했는데 보석이 안 들어와요");
        http.exchange("/api/issues/" + id + "/status", HttpMethod.PATCH, withKey("test-agent-key", Map.of("status", "IN_PROGRESS", "reason", "결제 문제라 바로 확인")), Map.class);
        var row = jdbc.queryForMap("select actor, detail from audit_log where target = ?", "issue:" + id);
        assertThat(row.get("actor")).isEqualTo("ai-agent");
        assertThat((String) row.get("detail")).contains("결제 문제라 바로 확인");
    }

    @Test
    void 에이전트_키로는_해결_보류로_닫을_수_없다() {
        long id = report("gemtower", "타워 3층에서 튕겨요");
        for (String st : new String[]{"RESOLVED", "WONT_FIX"}) {
            assertThat(http.exchange("/api/issues/" + id + "/status", HttpMethod.PATCH, withKey("test-agent-key", Map.of("status", st)), Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(http.exchange("/api/issues/" + id + "/status", HttpMethod.PATCH, withKey("test-admin-key", Map.of("status", "RESOLVED")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
