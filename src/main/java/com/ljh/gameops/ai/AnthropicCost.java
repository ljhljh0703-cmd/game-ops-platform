// Anthropic 실제 사용 금액(결정 38) — 조직 관리 키(ANTHROPIC_ADMIN_KEY, sk-ant-admin…)가 있으면 공식 비용 보고(/v1/organizations/cost_report)로
// 「잔액을 적은 날 이후 계정 전체가 쓴 돈」을 읽는다. 이 서버만이 아니라 같은 계정의 다른 사용(Claude Code API 등)까지 들어간다.
// 남은 크레딧 자체를 알려 주는 공식 API 는 없다(2026-10 확인). 그래서 작가가 콘솔에서 본 잔액을 한 번 적고, 그 뒤 쓴 돈을 뺀다.
// 키가 없거나 실패하면 null → 이 서버가 기록한 비용으로 뺀다. 한 시간에 한 번만 묻는다
package com.ljh.gameops.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

@Component
public class AnthropicCost {
    private static final Logger log = LoggerFactory.getLogger(AnthropicCost.class);
    static final Duration CACHE = Duration.ofHours(1);

    private final Clock clock;
    /** since → 그 뒤 쓴 돈(달러). 시험에서 바꿔 끼운다 */
    Function<OffsetDateTime, BigDecimal> fetcher;
    private BigDecimal cached;
    private Instant cachedAt;
    private OffsetDateTime cachedSince;
    private Instant failedAt;

    public AnthropicCost(Clock clock, @Value("${gameops.ai.admin-key:}") String adminKey,
                         @Value("${gameops.ai.admin-api-url:https://api.anthropic.com}") String apiUrl) {
        this.clock = clock;
        this.fetcher = adminKey == null || adminKey.isBlank() ? null : http(adminKey.strip(), apiUrl);
    }

    public boolean ready() { return fetcher != null; }

    /** since 이후 계정 전체 사용 금액(달러). 못 읽으면 null */
    public synchronized BigDecimal spentSince(OffsetDateTime since) {
        if (fetcher == null || since == null) return null;
        Instant now = clock.instant();
        if (cached != null && since.equals(cachedSince) && cachedAt.plus(CACHE).isAfter(now)) return cached;
        if (failedAt != null && failedAt.plus(Duration.ofMinutes(10)).isAfter(now)) return null; // 실패하면 10분 쉬고 다시(분류가 느려지지 않게)
        try {
            cached = fetcher.apply(since);
            cachedAt = now;
            cachedSince = since;
            return cached;
        } catch (RuntimeException e) {
            failedAt = now;
            log.warn("Anthropic 비용 보고를 못 읽었어요(이 서버 기록으로 계산): {}", e.getMessage());
            return null;
        }
    }

    private static Function<OffsetDateTime, BigDecimal> http(String key, String apiUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(20));
        RestClient client = RestClient.builder().baseUrl(apiUrl).requestFactory(rf)
                .defaultHeader("x-api-key", key).defaultHeader("anthropic-version", "2023-06-01").build();
        return since -> {
            // 비용 보고는 하루 단위라 잔액을 적은 날 0시(UTC)부터 묻는다
            String start = since.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant().toString();
            BigDecimal cents = BigDecimal.ZERO;
            String page = null;
            for (int i = 0; i < 20; i++) {
                String uri = "/v1/organizations/cost_report?starting_at=" + start + "&limit=31" + (page == null ? "" : "&page=" + page);
                JsonNode r = client.get().uri(uri).retrieve().body(JsonNode.class);
                if (r == null) break;
                for (JsonNode bucket : r.path("data")) {
                    for (JsonNode x : bucket.path("results")) cents = cents.add(new BigDecimal(x.path("amount").asText("0")));
                }
                if (!r.path("has_more").asBoolean(false)) break;
                page = r.path("next_page").asText(null);
                if (page == null) break;
            }
            return cents.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        };
    }

    /** 시험용 */
    void use(Function<OffsetDateTime, BigDecimal> f) {
        this.fetcher = f;
        this.cached = null;
        this.failedAt = null;
    }
}
