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
    /**
     * 남은 크레딧 추정(결정 38) — 작가가 적은 잔액(balance, 적은 때 at) − 그 뒤 쓴 돈(spent).
     * fromAnthropic = 쓴 돈을 Anthropic 공식 비용 보고(계정 전체)에서 읽었는지, 아니면 이 서버 기록인지
     */
    public record Credit(BigDecimal balance, OffsetDateTime at, BigDecimal spent, boolean fromAnthropic) {
        public BigDecimal remaining() { return balance.subtract(spent); }

        public int percentLeft() {
            return balance.signum() <= 0 ? 0 : remaining().multiply(BigDecimal.valueOf(100)).divide(balance, 0, RoundingMode.DOWN).max(BigDecimal.ZERO).intValue();
        }

        /** 「크레딧 약 $9.87 남음(10/21 잔액 $10.00 − 이후 $0.13, Anthropic 집계)」 */
        public String line() {
            return "크레딧 약 $" + remaining().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString() + " 남음("
                    + at.atZoneSameInstant(ZONE).format(DateTimeFormatter.ofPattern("M/d")) + " 잔액 $" + balance.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + " − 이후 $" + spent.setScale(2, RoundingMode.HALF_UP).toPlainString() + ", " + (fromAnthropic ? "Anthropic 집계" : "이 서버 기록") + ")";
        }
    }

    public record Month(String month, long calls, long failed, long inputTokens, long outputTokens, BigDecimal cost,
                        BigDecimal budget, BigDecimal remaining, int percent, Map<String, long[]> callsByPurpose,
                        Map<String, BigDecimal> costByPurpose, OffsetDateTime since, Credit credit) {
        public boolean limited() { return budget != null && budget.signum() > 0; }

        /** 「이번 달 AI 약 $0.0123 · 분류 40회 · 보고서 2회 / 예산 $5.00 · 남은 $4.99」 */
        public String line() {
            StringBuilder s = new StringBuilder("이번 달 AI 약 $").append(cost.setScale(4, RoundingMode.HALF_UP).toPlainString());
            callsByPurpose.forEach((k, v) -> s.append(" · ").append(label(k)).append(' ').append(v[0]).append("회"));
            if (failed > 0) s.append(" · 실패 ").append(failed).append("회");
            if (limited()) s.append(" / 예산 $").append(budget.setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append(" · 남은 $").append(remaining.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append(" (").append(percent).append("%)");
            if (credit != null) s.append(" · ").append(credit.line());
            return s.toString();
        }
    }

    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final Clock clock;
    private final Prices prices;
    private final BigDecimal budget;
    private final BigDecimal creditBalance;
    private final OffsetDateTime creditAt;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    AnthropicCost anthropic;

    public AiUsage(JdbcTemplate jdbc, OutboxService outbox, Clock clock,
                   @Value("${gameops.ai.price-input-per-mtok:0.10}") double in,
                   @Value("${gameops.ai.price-output-per-mtok:0.50}") double out,
                   @Value("${gameops.ai.price-input-long-per-mtok:0.50}") double inLong,
                   @Value("${gameops.ai.price-output-long-per-mtok:2.50}") double outLong,
                   @Value("${gameops.ai.long-prompt-tokens:100000}") int longThreshold,
                   @Value("${gameops.ai.monthly-budget-usd:5}") double budget) {
        this(jdbc, outbox, clock, in, out, inLong, outLong, longThreshold, budget, "", "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiUsage(JdbcTemplate jdbc, OutboxService outbox, Clock clock,
                   @Value("${gameops.ai.price-input-per-mtok:0.10}") double in,
                   @Value("${gameops.ai.price-output-per-mtok:0.50}") double out,
                   @Value("${gameops.ai.price-input-long-per-mtok:0.50}") double inLong,
                   @Value("${gameops.ai.price-output-long-per-mtok:2.50}") double outLong,
                   @Value("${gameops.ai.long-prompt-tokens:100000}") int longThreshold,
                   @Value("${gameops.ai.monthly-budget-usd:5}") double budget,
                   @Value("${gameops.ai.credit-balance-usd:}") String creditBalance,
                   @Value("${gameops.ai.credit-balance-at:}") String creditAt) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
        this.prices = new Prices(in, out, inLong, outLong, longThreshold);
        this.budget = BigDecimal.valueOf(budget);
        this.creditBalance = creditBalance == null || creditBalance.isBlank() ? null : new BigDecimal(creditBalance.strip());
        this.creditAt = this.creditBalance == null ? null : parseAt(creditAt, clock);
    }

    /** 「2026-10-21」 또는 「2026-10-21T10:50」(한국 시간). 비우면 서버를 켠 때 */
    static OffsetDateTime parseAt(String s, Clock clock) {
        if (s == null || s.isBlank()) return OffsetDateTime.now(clock);
        String t = s.strip();
        if (t.length() == 10) return java.time.LocalDate.parse(t).atStartOfDay(ZONE).toOffsetDateTime();
        return java.time.LocalDateTime.parse(t).atZone(ZONE).toOffsetDateTime();
    }

    /** 남은 크레딧 추정. 잔액을 안 적었으면 null */
    public Credit credit() {
        if (creditBalance == null) return null;
        BigDecimal real = anthropic == null ? null : anthropic.spentSince(creditAt);
        if (real != null) return new Credit(creditBalance, creditAt, real, true);
        BigDecimal mine = jdbc.queryForObject("SELECT coalesce(sum(cost_usd), 0) FROM ai_usage WHERE at >= ?", BigDecimal.class, creditAt);
        return new Credit(creditBalance, creditAt, mine, false);
    }

    public static String label(String purpose) {
        return switch (purpose) {
            case CLASSIFY -> "분류";
            case DIGEST -> "보고서";
            case "dedupe" -> "묶기 판정";
            case "translate" -> "번역";
            case "patch" -> "패치노트";
            default -> purpose;
        };
    }

    /**
     * 한 시간 AI 호출 상한(결정 43). 서로 다른 글을 잔뜩 보내 번역·판정을 계속 부르게 하는 비용 공격을 막는다.
     * 넘으면 그 시간엔 규칙·글자 비교만(제보는 그대로 받는다). 0 이면 끔. 15초마다 다시 센다
     */
    @Value("${gameops.ai.max-calls-per-hour:600}")
    int maxCallsPerHour = 600;
    private volatile long hourCountAt;
    private volatile long hourCount;
    private volatile boolean hourAlerted;

    boolean underHourlyCap() {
        if (maxCallsPerHour <= 0) return true;
        long now = clock.millis();
        if (now - hourCountAt > 15_000) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM ai_usage WHERE at >= ?", Long.class, OffsetDateTime.now(clock).minusHours(1));
            hourCount = n == null ? 0 : n;
            hourCountAt = now;
        }
        boolean ok = hourCount < maxCallsPerHour;
        if (!ok && !hourAlerted) {
            hourAlerted = true;
            log.warn("AI 호출이 한 시간 {}번을 넘어 규칙으로만 처리합니다", maxCallsPerHour);
            try {
                String hour = OffsetDateTime.now(clock).atZoneSameInstant(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"));
                outbox.enqueueOnce(NotificationType.OPS_ALERT, Map.of("text", "🛡️ AI 호출이 한 시간에 " + maxCallsPerHour
                        + "번을 넘었어요. 이번 한 시간은 AI 없이(규칙·글자 비교) 받습니다. 제보는 그대로 들어와요. 평소보다 많으면 제보 몰림이나 비용 공격일 수 있어요."),
                        "ai-hour:" + hour);
            } catch (RuntimeException e) {
                log.warn("AI 시간 상한 알림 실패: {}", e.getMessage());
            }
        }
        if (ok) hourAlerted = false;
        return ok;
    }

    /** 이번 달 예산이 남았는지. 예산 0 은 제한 없음 */
    public boolean allowed() {
        if (!underHourlyCap()) return false;
        // 크레딧이 바닥나면(추정) 부르지 않는다. 불러도 Anthropic 이 거절한다
        Credit c = credit();
        if (c != null && c.remaining().signum() <= 0) return false;
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
        Credit c = credit();
        if (c != null) {
            int left = c.percentLeft();
            for (int level : new int[]{0, 20}) { // 남은 크레딧 20% 아래·바닥
                if (left <= level) {
                    String text = level == 0
                            ? "💸 Anthropic 크레딧이 바닥난 것 같아요. AI 를 멈추고 규칙·기록만으로 돌려요. 콘솔에서 충전한 뒤 AI_CREDIT_BALANCE_USD 를 새 잔액으로 바꿔 주세요. (" + c.line() + ")"
                            : "💸 Anthropic 크레딧이 " + left + "% 남았어요. (" + c.line() + ")";
                    outbox.enqueueOnce(NotificationType.AI_BUDGET, Map.of("text", text), "ai-credit:" + c.at().toLocalDate() + ":" + c.balance().toPlainString() + ":" + level);
                    break;
                }
            }
        }
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
                b, remaining, pct, calls, costs, start, credit());
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
