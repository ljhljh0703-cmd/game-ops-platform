// 디스코드 설정 — 꺼진 것이 기본값, 실제 연결은 토큰을 넣고 enabled=true 로
package com.ljh.gameops.channel.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gameops.discord")
public record DiscordProperties(boolean enabled, String botToken, List<String> reportChannelIds, String defaultGame) {
    public DiscordProperties {
        reportChannelIds = reportChannelIds == null ? List.of()
                : reportChannelIds.stream().filter(s -> s != null && !s.isBlank()).toList();
    }
}
