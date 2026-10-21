// AI 사용량·비용·예산(결정 34) — 서버가 AI(분류·피드백 정리)를 부를 때마다 쓴 토큰을 응답에서 읽어 기록하고,
// 설정한 단가로 비용(달러)을 계산한다. 이번 달 합계가 예산을 넘으면 더 부르지 않는다(분류는 키워드 규칙, 정리는 기록만으로 같은 모양).
// 80%·100%에 닿으면 슬랙 긴급 채널에 한 번씩 알린다. 단가는 Anthropic 공개 가격표 값(2026-10 확인)을 기본으로 두고 설정으로 바꾼다.
// Claude 플러그인(맥의 Claude 앱)·Codex 는 각자 구독으로 돌아 여기 잡히지 않는다
package com.ljh.gameops.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiUsage {
    private static final Logger log = LoggerFactory.getLogger(AiUsage.class);
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final String CLASSIFY = "classify", DIGEST = "digest";

    /** 백만 토큰당 달러. long = 프롬프트가 longThreshold 토큰을 넘을 때 */
    public record Prices(double input, double output, double inputLong, double outputLong, int longThreshold) {}

    /** byPurpose = 용도별 [호출 수, 비용] · remaining = 예산 - 쓴 돈(예산이 0이면 null) */
    public record Month(String month, long calls, long failed, long inputTokens, long outputTokens, BigDecimal cost,
                        BigDecimal budget, BigDecimal remaining, int percent, Map<String, long[]> callsByPurpose,
                        Map<String, BigDecimal> costByPurpose, OffsetDateTime since) {
        public boolean limited() { return budget != null && budget.signum() > 0; }

        /** 「이번 달 AI 약 $0.0123 · 분류 40회 · 보고서 2회 / 예산 $5.00 · 남은 $4.99」 */
        public String line() {
            StringBuilder s = new StringBuilder("이번 달 AI 약 $").append(cost.setScale(4, RoundingMode.HALF_UP).toPlainString());
            callsByPurpose.forEach((k, v) -> s.append(" · ").append(label(k)).append(' ').append(v[0]).append("회"));
            if (failed > 0) s.append(" · 실패 ").append(failed).append("회");
            if (limited()) s.append(" / 예산 $").append(budget.setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append(" · 남은 $").append(remaining.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append(" (").append(percent).append("%)");
            return s.toString();
        }
    }

    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final Clock clock;
    private final Prices prices;
    private final BigDecimal budget;

    public AiUsage(JdbcTemplate jdbc, OutboxService outbox, Clock clock,
                   @Value("${gameops.ai.price-input-per-mtok:0.10}") double in,
                   @Value("${gameops.ai.price-output-per-mtok:0.50}") double out,
                   @Value("${gameops.ai.price-input-long-per-mtok:0.50}") double inLong,
                   @Value("${gameops.ai.price-output-long-per-mtok:2.50}") double outLong,
                   @Value("${gameops.ai.long-prompt-tokens:100000}") int longThreshold,
                   @Value("${gameops.ai.monthly-budget-usd:5}") double budget) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
        this.prices = new Prices(in, out, inLong, outLong, longThreshold);
        this.budget = BigDecimal.valueOf(budget);
    }

    public static String label(String purpose) {
        return switch (purpose) {
            case CLASSIFY -> "분류";
            case DIGEST -> "보고서";
            default -> purpose;
        };
    }

    /** 이번 달 예산이 남았는지. 예산 0 은 제한 없음 */
    public boolean allowed() {
        if (budget.signum() <= 0) return true;
        return spent(monthStart()).compareTo(budget) < 0;
    }

    /** 이 호출의 비용(달러) */
    BigDecimal cost(int inTok, int outTok) {
        boolean longPrompt = inTok > prices.longThreshold();
        double usd = inTok / 1_000_000.0 * (longPrompt ? prices.inputLong() : prices.input())
                + outTok / 1_000_000.0 * (longPrompt ? prices.outputLong() : prices.output());
        return BigDecimal.valueOf(usd).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 응답에서 토큰 수를 읽어 기록한다. OpenAI 호환(usage.prompt_tokens·completion_tokens)과
     * Anthropic(usage.input_tokens·output_tokens) 둘 다 읽는다. 실패한 호출은 ok=false, 토큰 0(응답이 없으면 과금도 없다고 본다)
     */
    public void record(String purpose, String model, JsonNode response, boolean ok) {
        JsonNode u = response == null ? null : response.path("usage");
        int in = u == null ? 0 : first(u, "prompt_tokens", "input_tokens");
        int out = u == null ? 0 : first(u, "completion_tokens", "output_tokens");
        try {
            jdbc.update("INSERT INTO ai_usage (at, purpose, model, input_tokens, output_tokens, cost_usd, ok) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    OffsetDateTime.now(clock), purpose, model == null ? "?" : model, in, out, cost(in, out), ok);
            alertIfNeeded();
        } catch (RuntimeException e) {
            log.warn("AI 사용 기록 실패(호출은 그대로): {}", e.getMessage()); // 기록이 막혀도 접수는 멈추지 않는다
        }
    }

    /** 80%·100% 에 처음 닿을 때 한 번씩(같은 달·같은 단계는 대기열 키로 한 번) */
    void alertIfNeeded() {
        if (budget.signum() <= 0) return;
        Month m = month();
        for (int level : new int[]{100, 80}) {
            if (m.percent() >= level) {
                String text = level >= 100
                        ? "💸 이번 달 AI 예산 $" + budget.toPlainString() + " 을 다 썼어요. 다음 달까지 분류는 키워드 규칙으로, 보고서는 기록만으로 만들어요. (" + m.line() + ")"
                        : "💸 이번 달 AI 예산의 " + m.percent() + "% 를 썼어요. (" + m.line() + ")";
                outbox.enqueueOnce(NotificationType.AI_BUDGET, Map.of("text", text), "ai-budget:" + m.month() + ":" + level);
                return;
            }
        }
    }

    public Month month() {
        OffsetDateTime start = monthStart();
        Map<String, long[]> calls = new LinkedHashMap<>();
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        long[] tot = new long[4]; // calls, failed, in, out
        BigDecimal[] cost = {BigDecimal.ZERO};
        jdbc.query("""
                SELECT purpose, count(*), count(*) FILTER (WHERE NOT ok), coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0),
                       coalesce(sum(cost_usd), 0)
                FROM ai_usage WHERE at >= ? GROUP BY purpose ORDER BY purpose
                """, rs -> {
            String p = rs.getString(1);
            calls.put(p, new long[]{rs.getLong(2)});
            costs.put(p, rs.getBigDecimal(6));
            tot[0] += rs.getLong(2); tot[1] += rs.getLong(3); tot[2] += rs.getLong(4); tot[3] += rs.getLong(5);
            cost[0] = cost[0].add(rs.getBigDecimal(6));
        }, start);
        BigDecimal b = budget.signum() > 0 ? budget : null;
        BigDecimal remaining = b == null ? null : b.subtract(cost[0]);
        int pct = b == null ? 0 : cost[0].multiply(BigDecimal.valueOf(100)).divide(b, 0, RoundingMode.DOWN).intValue();
        return new Month(start.format(DateTimeFormatter.ofPattern("yyyy-MM")), tot[0], tot[1], tot[2], tot[3], cost[0],
                b, remaining, pct, calls, costs, start);
    }

    /** 기록을 시작한 뒤 전체 합계(달러) */
    public BigDecimal allTime() {
        return jdbc.queryForObject("SELECT coalesce(sum(cost_usd), 0) FROM ai_usage", BigDecimal.class);
    }

    public Prices prices() { return prices; }

    private BigDecimal spent(OffsetDateTime since) {
        return jdbc.queryForObject("SELECT coalesce(sum(cost_usd), 0) FROM ai_usage WHERE at >= ?", BigDecimal.class, since);
    }

    OffsetDateTime monthStart() {
        return OffsetDateTime.now(clock).atZoneSameInstant(ZONE).withDayOfMonth(1).toLocalDate().atStartOfDay(ZONE).toOffsetDateTime();
    }

    private static int first(JsonNode u, String a, String b) {
        if (u.hasNonNull(a)) return u.path(a).asInt();
        return u.path(b).asInt(0);
    }
}
