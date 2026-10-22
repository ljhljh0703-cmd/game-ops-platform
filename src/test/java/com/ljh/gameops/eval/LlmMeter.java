// LLM 평가용 계량기 — 호출마다 걸린 시간과 토큰 수를 모아 「한 번에 얼마·몇 초」를 낸다(운영 코드는 건드리지 않음)
package com.ljh.gameops.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.StreamUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LlmMeter {
    public record Call(String purpose, long millis, int in, int out, boolean ok) {}

    /** 운영과 같은 단가(application.yml gameops.ai.price-*). 달러/백만 토큰 */
    static final double PRICE_IN = 0.10, PRICE_OUT = 0.50;

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String purpose = "classify";

    public void purpose(String p) { purpose = p; }

    public ClientHttpRequestInterceptor interceptor() {
        return (req, body, exec) -> {
            long t0 = System.nanoTime();
            try {
                var res = exec.execute(req, body);
                // 응답 코드·본문은 읽을 때 기다리므로 본문을 다 읽은 뒤에 시간을 잰다(Buffering 팩토리라 다시 읽을 수 있다)
                boolean ok = res.getStatusCode().is2xxSuccessful();
                byte[] bytes = StreamUtils.copyToByteArray(res.getBody());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int in = 0, out = 0;
                try {
                    JsonNode u = mapper.readTree(bytes).path("usage");
                    in = u.path("prompt_tokens").asInt(u.path("input_tokens").asInt());
                    out = u.path("completion_tokens").asInt(u.path("output_tokens").asInt());
                } catch (Exception ignore) { }
                calls.add(new Call(purpose, ms, in, out, ok));
                return res;
            } catch (java.io.IOException e) {
                calls.add(new Call(purpose, (System.nanoTime() - t0) / 1_000_000, 0, 0, false));
                throw e;
            }
        };
    }

    public List<Call> calls(String p) { synchronized (calls) { return calls.stream().filter(c -> c.purpose.equals(p)).toList(); } }

    static double cost(Call c) { return (c.in * PRICE_IN + c.out * PRICE_OUT) / 1_000_000; }

    /** 표 한 줄: 호출 수 · 실패 · 중앙값/95% 시간 · 평균 토큰 · 한 번 비용 · 1,000건 비용 */
    public String row(String label, String p) {
        List<Call> cs = calls(p);
        if (cs.isEmpty()) return "| " + label + " | 0 | — | — | — | — | — | — |\n";
        List<Long> ms = cs.stream().map(Call::millis).sorted().toList();
        long p50 = ms.get(ms.size() / 2), p95 = ms.get(Math.min(ms.size() - 1, (int) Math.ceil(ms.size() * 0.95) - 1));
        double avgIn = cs.stream().mapToInt(Call::in).average().orElse(0), avgOut = cs.stream().mapToInt(Call::out).average().orElse(0);
        double per = cs.stream().mapToDouble(LlmMeter::cost).average().orElse(0);
        long fail = cs.stream().filter(c -> !c.ok).count();
        return String.format(Locale.ROOT, "| %s | %d | %d | %.2f초 | %.2f초 | %.0f / %.0f | $%.6f | $%.3f |%n",
                label, cs.size(), fail, p50 / 1000.0, p95 / 1000.0, avgIn, avgOut, per, per * 1000);
    }
}
