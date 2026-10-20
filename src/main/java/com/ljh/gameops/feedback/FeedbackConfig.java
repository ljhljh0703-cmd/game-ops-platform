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
    DigestWriter digestWriter(ClassifierProperties props, ObjectMapper mapper) {
        ClassifierProperties.Llm llm = props.llm();
        if (llm == null || llm.apiKey() == null || llm.apiKey().isBlank()) return new DigestWriter(null, mapper);
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(60));
        RestClient client = RestClient.builder().baseUrl(llm.baseUrl()).requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + llm.apiKey()).build();
        String model = llm.model();
        DigestWriter.Ai ai = (system, user) -> {
            JsonNode r = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "temperature", 0, "max_tokens", MAX_TOKENS, "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user))))
                    .retrieve().body(JsonNode.class);
            return r == null ? null : r.path("choices").path(0).path("message").path("content").asText(null);
        };
        return new DigestWriter(ai, mapper);
    }
}
