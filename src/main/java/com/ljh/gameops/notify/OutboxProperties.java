// 알림 재시도 설정
package com.ljh.gameops.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gameops.outbox")
public record OutboxProperties(int batchSize, int maxAttempts, long baseBackoffMs, long pollMs) {}
