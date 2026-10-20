// 슬랙 설정 — 심각도 높은 이슈는 urgent 채널, 나머지는 default 채널
package com.ljh.gameops.channel.slack;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gameops.slack")
public record SlackProperties(boolean enabled, String botToken, String signingSecret, Channels channels, Identity identity) {
    public record Channels(String defaultChannel, String urgent) {}

    /** 카드마다 봇 이름·아이콘 바꾸기. 슬랙 앱에 chat:write.customize 권한을 더하고 다시 설치한 뒤 enabled=true */
    public record Identity(boolean enabled, String name, String icon) {}

    // 생성자가 둘이라 설정을 읽을 생성자를 정해 준다
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public SlackProperties {}

    public SlackProperties(boolean enabled, String botToken, String signingSecret, Channels channels) {
        this(enabled, botToken, signingSecret, channels, null);
    }
}
