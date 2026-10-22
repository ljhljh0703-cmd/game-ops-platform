// AI 호출 본문 한 곳 — OpenAI 호환 /chat/completions 모양.
// Anthropic 모델(claude-haiku-5-5)은 temperature 를 받지 않고, 생각 단계를 켜 두면 짧은 JSON 답이 토큰 한도에 잘려 빈 답이 온다.
// 그래서 Anthropic 주소면 생각 단계를 끈다(2026-10-22 평가에서 찾음, docs/EVAL.md).
package com.ljh.gameops.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LlmRequest {
    private LlmRequest() {}

    public static boolean isAnthropic(String baseUrl) {
        return baseUrl != null && baseUrl.contains("anthropic.com");
    }

    public static Map<String, Object> chat(boolean anthropic, String model, int maxTokens, String system, String user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("max_tokens", maxTokens);
        if (anthropic) m.put("thinking", Map.of("type", "disabled"));
        m.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        return m;
    }
}
