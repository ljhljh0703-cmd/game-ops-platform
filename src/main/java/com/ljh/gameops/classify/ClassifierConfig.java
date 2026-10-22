// 설정에 따라 규칙 분류기 또는 LLM+규칙 내려가기 분류기를 만든다
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
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
    ManagedRuleSource ruleSource(ClassifierProperties props, ObjectProvider<RuleVersionRepository> versions) {
        RuleSource bundled = new BundledRuleSource();
        String file = props.rulesFile();
        RuleSource fileOrBundled = (file == null || file.isBlank()) ? bundled : new FileRuleSource(Path.of(file), bundled, EvalRuleGate.fromClasspath());
        // 파일(사람) 위에 API 로 반영한 수정(AI)을 얹는다 — 사람이 파일을 고치면 파일이 이긴다
        return new ManagedRuleSource(fileOrBundled, versions);
    }

    @Bean
    RuleBasedClassifier ruleBasedClassifier(ManagedRuleSource source) {
        return new RuleBasedClassifier(source);
    }

    @Bean
    RuleGate ruleGate() {
        return EvalRuleGate.fromClasspath();
    }

    @Bean
    @Primary
    IssueClassifier issueClassifier(ClassifierProperties props, RuleBasedClassifier rules, ObjectMapper mapper,
                                    com.ljh.gameops.ai.AiUsage usage) {
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
        return new FallbackClassifier(new LlmClassifier(client, llm.model(), mapper, usage,
                com.ljh.gameops.ai.LlmRequest.isAnthropic(llm.baseUrl())), rules);
    }
}
