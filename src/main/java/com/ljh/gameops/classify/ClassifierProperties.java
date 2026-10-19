// 분류기 설정 — LLM은 꺼진 것이 기본값
package com.ljh.gameops.classify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gameops.classifier.llm")
public record ClassifierProperties(boolean enabled, String baseUrl, String apiKey, String model, int timeoutSeconds) {}
