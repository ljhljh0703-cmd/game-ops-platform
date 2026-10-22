// AI 묶기 판정 연결 — 분류기와 같은 AI 설정(LLM_*)과 같은 예산. 키가 있고 gameops.dedupe.ai-judge 가 켜져 있으면 쓴다
package com.ljh.gameops.intake;

import com.ljh.gameops.ai.AiUsage;
import com.ljh.gameops.ai.LlmRequest;
import com.ljh.gameops.classify.ClassifierProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class DuplicateJudgeConfig {
    @Bean
    DuplicateJudge duplicateJudge(ClassifierProperties props, AiUsage usage,
                                  @Value("${gameops.dedupe.ai-judge:true}") boolean on) {
        ClassifierProperties.Llm llm = props.llm();
        if (!on || llm == null || llm.apiKey() == null || llm.apiKey().isBlank()) return DuplicateJudge.NONE;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(3));
        rf.setReadTimeout(Duration.ofSeconds(8));
        RestClient client = RestClient.builder().baseUrl(llm.baseUrl()).requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + llm.apiKey()).build();
        return new DuplicateJudge.Llm(client, llm.model(), LlmRequest.isAnthropic(llm.baseUrl()), usage);
    }
}
