// 패치노트 글쓰기용 AI 연결 — 분류·보고서와 같은 AI 설정(LLM_*)과 같은 예산(결정 34)을 쓴다. 키가 없으면 기록만으로
package com.ljh.gameops.patch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.ai.AiUsage;
import com.ljh.gameops.classify.ClassifierProperties;
import com.ljh.gameops.feedback.DigestWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
public class PatchConfig {
    public static final String PURPOSE = "patch";

    @Bean
    PatchNoteWriter patchNoteWriter(ClassifierProperties props, ObjectMapper mapper, AiUsage usage) {
        ClassifierProperties.Llm llm = props.llm();
        if (llm == null || llm.apiKey() == null || llm.apiKey().isBlank()) return new PatchNoteWriter(null, mapper);
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(60));
        RestClient client = RestClient.builder().baseUrl(llm.baseUrl()).requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + llm.apiKey()).build();
        String model = llm.model();
        boolean anthropic = com.ljh.gameops.ai.LlmRequest.isAnthropic(llm.baseUrl());
        DigestWriter.Ai ai = (system, user) -> {
            if (!usage.allowed()) throw new IllegalStateException("이번 달 AI 예산을 다 썼어요");
            JsonNode r;
            try {
                r = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .body(com.ljh.gameops.ai.LlmRequest.chat(anthropic, model, 1000, system, user))
                        .retrieve().body(JsonNode.class);
            } catch (RuntimeException e) {
                usage.record(PURPOSE, model, null, false);
                throw e;
            }
            usage.record(PURPOSE, model, r, true);
            return r == null ? null : r.path("choices").path(0).path("message").path("content").asText(null);
        };
        return new PatchNoteWriter(ai, mapper);
    }
}
