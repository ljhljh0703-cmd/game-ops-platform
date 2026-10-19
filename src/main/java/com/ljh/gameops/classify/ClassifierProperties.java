// 분류기 설정 — LLM은 꺼진 것이 기본값, 규칙 파일 경로가 비어 있으면 내장 규칙
package com.ljh.gameops.classify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gameops.classifier")
public record ClassifierProperties(String rulesFile, Llm llm) {
    public record Llm(boolean enabled, String baseUrl, String apiKey, String model, int timeoutSeconds) {}
}
