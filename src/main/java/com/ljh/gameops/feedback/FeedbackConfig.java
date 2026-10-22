// 피드백 정리용 AI 연결 — 분류기와 같은 AI 설정(LLM_*)을 쓴다. 키만 있으면 켜진다(분류기를 AI 로 돌리는지와 따로).
// 정리는 글이 길어 분류(10초)보다 넉넉히 기다린다.
package com.ljh.gameops.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.classify.ClassifierProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
public class FeedbackConfig {
    static final int MAX_TOKENS = 1200;

    @Bean
    DigestWriter digestWriter(ClassifierProperties props, ObjectMapper mapper, com.ljh.gameops.ai.AiUsage usage) {
        ClassifierProperties.Llm llm = props.llm();
        if (llm == null || llm.apiKey() == null || llm.apiKey().isBlank()) return new DigestWriter(null, mapper);
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(60));
        RestClient client = RestClient.builder().baseUrl(llm.baseUrl()).requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + llm.apiKey()).build();
        String model = llm.model();
        boolean anthropic = com.ljh.gameops.ai.LlmRequest.isAnthropic(llm.baseUrl());
        DigestWriter.Ai ai = (system, user) -> {
            // 예산을 다 썼으면 부르지 않는다 → 보고서는 기록만으로 같은 모양(DigestWriter 가 실패를 그렇게 받는다)
            if (!usage.allowed()) throw new IllegalStateException("이번 달 AI 예산을 다 썼어요");
            JsonNode r;
            try {
                r = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .body(com.ljh.gameops.ai.LlmRequest.chat(anthropic, model, MAX_TOKENS, system, user))
                        .retrieve().body(JsonNode.class);
            } catch (RuntimeException e) {
                usage.record(com.ljh.gameops.ai.AiUsage.DIGEST, model, null, false);
                throw e;
            }
            usage.record(com.ljh.gameops.ai.AiUsage.DIGEST, model, r, true);
            return r == null ? null : r.path("choices").path(0).path("message").path("content").asText(null);
        };
        return new DigestWriter(ai, mapper);
    }
}
