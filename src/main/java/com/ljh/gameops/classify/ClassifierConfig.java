// 설정에 따라 규칙 분류기 또는 LLM+규칙 내려가기 분류기를 만든다
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ClassifierConfig {
    @Bean
    IssueClassifier issueClassifier(ClassifierProperties props, ObjectMapper mapper) {
        IssueClassifier rules = new RuleBasedClassifier();
        if (!props.enabled()) return rules;
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("gameops.classifier.llm.enabled=true 인데 api-key 가 비어 있습니다");
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, props.timeoutSeconds()));
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        RestClient client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .build();
        return new FallbackClassifier(new LlmClassifier(client, props.model(), mapper), rules);
    }
}
