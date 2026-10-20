// 최근 피드백 모아 보기와 AI 정리 올리기 — 키 필요, 게임별로 모으고, 정리는 기록 + 슬랙 카드 대기열
package com.ljh.gameops.feedback;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.notify.NotificationRenderer;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackTest extends IntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;
    @Autowired OutboxRepository outbox;
    @Autowired NotificationRenderer renderer;

    private HttpHeaders key(String k) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", k);
        return h;
    }

    @Test
    void 최근_제보를_게임별로_모아_본다() {
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "b", "보스웨이브에서 튕겨요ㅠ", null));
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "c", "하트 충전이 안 돼요", null));
        var r = http.exchange("/api/feedback/recent?game=gemtower&days=7", HttpMethod.GET, new HttpEntity<>(key("test-agent-key")), List.class);
        assertThat(r.getBody()).hasSize(2);
        assertThat((Map<String, Object>) r.getBody().get(0)).containsEntry("category", "CRASH").containsEntry("issueReportCount", 2);
        var all = http.exchange("/api/feedback/recent", HttpMethod.GET, new HttpEntity<>(key("test-agent-key")), List.class);
        assertThat(all.getBody()).hasSize(3);
        assertThat(http.exchange("/api/feedback/recent", HttpMethod.GET, new HttpEntity<>(key("nope")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 정리를_올리면_기록하고_슬랙_정리_카드를_보낸다() {
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        String body = "{\"game\":\"gemtower\",\"days\":7,\"text\":\"*자주 나온 문제*\\n• 보스 웨이브 튕김 1건 <!channel>\"}";
        var r = http.exchange("/api/admin/digests", HttpMethod.POST, new HttpEntity<>(body, key("test-agent-key")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        var queued = outbox.findByTypeOrderByIdAsc(NotificationType.DIGEST);
        assertThat(queued).hasSize(1);
        var n = renderer.render(queued.get(0));
        assertThat(n.gameCode()).isEqualTo("gemtower");
        assertThat(n.digest().reportCount()).isEqualTo(1);
        var card = com.ljh.gameops.channel.slack.SlackCards.digestCard(n.digest());
        assertThat(card.getColor()).isEqualTo("#7C3AED");
        var text = ((com.slack.api.model.block.SectionBlock) card.getBlocks().get(2)).getText().getText();
        assertThat(text).contains("*자주 나온 문제*").doesNotContain("<!channel>").contains("&lt;!channel&gt;");
        assertThat(jdbc.queryForObject("select actor from feedback_digest", String.class)).isEqualTo("ai-agent");
    }

    @Test
    void 빈_정리는_400() {
        var r = http.exchange("/api/admin/digests", HttpMethod.POST, new HttpEntity<>("{\"text\":\" \"}", key("test-admin-key")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
