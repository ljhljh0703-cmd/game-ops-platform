// 관리자 설정 — REST API 키와 슬랙 사용자 ID 목록
package com.ljh.gameops.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "gameops.admin")
public record AdminProperties(Map<String, String> apiKeys, List<String> slackUserIds) {
    public AdminProperties {
        apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
        slackUserIds = slackUserIds == null ? List.of() : List.copyOf(slackUserIds);
    }
}
