// 설정에 따라 규칙 분류기 또는 LLM+규칙 내려가기 분류기를 만든다
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class ClassifierConfig {
    @Bean
    RuleBasedClassifier ruleBasedClassifier(ClassifierProperties props) {
        RuleSource bundled = new BundledRuleSource();
        String file = props.rulesFile();
        RuleSource source = (file == null || file.isBlank()) ? bundled : new FileRuleSource(Path.of(file), bundled, EvalRuleGate.fromClasspath());
        return new RuleBasedClassifier(source);
    }

    @Bean
    @Primary
    IssueClassifier issueClassifier(ClassifierProperties props, RuleBasedClassifier rules, ObjectMapper mapper) {
        ClassifierProperties.Llm llm = props.llm();
        if (llm == null || !llm.enabled()) return rules;
        if (llm.apiKey() == null || llm.apiKey().isBlank()) {
            throw new IllegalStateException("gameops.classifier.llm.enabled=true 인데 api-key 가 비어 있습니다");
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, llm.timeoutSeconds()));
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        RestClient client = RestClient.builder()
                .baseUrl(llm.baseUrl())
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + llm.apiKey())
                .build();
        return new FallbackClassifier(new LlmClassifier(client, llm.model(), mapper), rules);
    }
}
