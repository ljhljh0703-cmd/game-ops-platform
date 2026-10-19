// 슬랙 설정 — 심각도 높은 이슈는 urgent 채널, 나머지는 default 채널
package com.ljh.gameops.channel.slack;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gameops.slack")
public record SlackProperties(boolean enabled, String botToken, String signingSecret, Channels channels) {
    public record Channels(String defaultChannel, String urgent) {}
}
