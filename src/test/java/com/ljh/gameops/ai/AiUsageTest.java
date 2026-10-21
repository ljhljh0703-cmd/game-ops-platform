// AI 사용량·비용·예산(결정 34) — 응답의 토큰으로 비용 계산, 이번 달 합계, 예산을 넘으면 안 부르고 규칙으로, 80%·100% 알림은 한 번씩,
// 사용량 API·슬랙 명령 「/피드백정리 사용량」
package com.ljh.gameops.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackSignatureVerifier;
import com.ljh.gameops.classify.ClassifierException;
import com.ljh.gameops.classify.LlmClassifier;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.notify.OutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiUsageTest extends IntegrationTest {
    @Autowired AiUsage usage;
    @Autowired OutboxService outboxService;
    @Autowired OutboxRepository outbox;
    @Autowired Clock clock;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;

    private com.fasterxml.jackson.databind.JsonNode resp(int in, int out) throws Exception {
        return mapper.readTree("{\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + "}}");
    }

    private AiUsage withBudget(double usd) {
        return new AiUsage(jdbc, outboxService, clock, 0.10, 0.50, 0.50, 2.50, 100_000, usd);
    }

    @Test
    void 응답의_토큰으로_비용을_계산해_이번_달에_더한다() throws Exception {
        usage.record(AiUsage.CLASSIFY, "claude-haiku-5-5", resp(2000, 100), true);
        usage.record(AiUsage.DIGEST, "claude-haiku-5-5", mapper.readTree("{\"usage\":{\"input_tokens\":10000,\"output_tokens\":1000}}"), true);
        usage.record(AiUsage.CLASSIFY, "claude-haiku-5-5", null, false);

        var m = usage.month();
        assertThat(m.calls()).isEqualTo(3);
        assertThat(m.failed()).isEqualTo(1);
        assertThat(m.inputTokens()).isEqualTo(12000);
        // 2000×0.10 + 100×0.50 + 10000×0.10 + 1000×0.50 (백만 토큰당) = 0.00025 + 0.0015
        assertThat(m.cost()).isEqualByComparingTo("0.00175");
        assertThat(m.line()).isEqualTo("이번 달 AI 약 $0.0018 · 분류 2회 · 보고서 1회 · 실패 1회 / 예산 $5.00 · 남은 $5.00 (0%)");
    }

    @Test
    void 프롬프트가_10만_토큰을_넘으면_비싼_단가() {
        assertThat(usage.cost(150_000, 1000)).isEqualByComparingTo("0.0775");
        assertThat(usage.cost(1000, 1000)).isEqualByComparingTo("0.0006");
    }

    @Test
    void 예산을_넘으면_더_부르지_않고_알림은_단계마다_한_번() throws Exception {
        AiUsage tight = withBudget(0.0003);
        tight.record(AiUsage.CLASSIFY, "m", resp(2000, 100), true); // $0.00025 = 83%
        assertThat(tight.allowed()).isTrue();
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.AI_BUDGET)).hasSize(1)
                .first().satisfies(o -> assertThat(o.getPayload()).contains("83%"));

        tight.record(AiUsage.CLASSIFY, "m", resp(2000, 100), true); // 166%
        tight.record(AiUsage.CLASSIFY, "m", resp(2000, 100), true);
        assertThat(tight.allowed()).isFalse();
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.AI_BUDGET)).hasSize(2)
                .last().satisfies(o -> assertThat(o.getPayload()).contains("다 썼어요", "키워드 규칙"));

        // 분류기는 부르기 전에 멈춘다(주소가 없는 곳이라 불렀다면 다른 오류가 난다) → FallbackClassifier 가 규칙으로
        var llm = new LlmClassifier(RestClient.builder().baseUrl("http://127.0.0.1:9").build(), "m", mapper, tight);
        assertThatThrownBy(() -> llm.classify("gemtower", "튕겨요")).isInstanceOf(ClassifierException.class).hasMessageContaining("예산");
    }

    @Test
    void 예산_0은_제한_없음() throws Exception {
        AiUsage free = withBudget(0);
        free.record(AiUsage.CLASSIFY, "m", resp(5_000_000, 0), true);
        assertThat(free.allowed()).isTrue();
        assertThat(free.month().line()).doesNotContain("예산");
    }

    @Test
    void 사용량_API와_슬랙_명령() throws Exception {
        usage.record(AiUsage.CLASSIFY, "claude-haiku-5-5", resp(2000, 100), true);
        HttpHeaders h = new HttpHeaders();
        h.set("X-Api-Key", "test-admin-key");
        var r = http.exchange("/api/admin/usage", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("aiCalls", 1).containsEntry("month", usage.month().month());
        assertThat(new BigDecimal(r.getBody().get("remainingUsd").toString())).isEqualByComparingTo("4.99975");
        assertThat(http.getForEntity("/api/admin/usage", Map.class).getStatusCode().value()).isEqualTo(403);

        String body = "command=" + enc("/피드백정리") + "&text=" + enc("사용량") + "&user_id=U_ADMIN&channel_id=C&channel_name=x&response_url=" + enc("https://example.com/x");
        long ts = Instant.now().getEpochSecond();
        HttpHeaders sh = new HttpHeaders();
        sh.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        sh.set("X-Slack-Request-Timestamp", String.valueOf(ts));
        sh.set("X-Slack-Signature", new SlackSignatureVerifier("test-signing-secret").sign(String.valueOf(ts), body));
        var s = http.exchange("/slack/commands", HttpMethod.POST, new HttpEntity<>(body, sh), Map.class);
        assertThat(s.getBody().get("text").toString()).contains("💸 이번 달 AI 약 $0.0003 · 분류 1회 / 예산 $5.00", "Codex");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
