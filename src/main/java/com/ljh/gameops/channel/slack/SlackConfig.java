// 슬랙이 켜져 있으면 슬랙 전송기를 알림 전송기로 쓴다
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.notify.NotificationSender;
import org.springframework.beans.factory.ObjectProvider;
import com.slack.api.Slack;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlackConfig {
    @Bean
    @ConditionalOnProperty(prefix = "gameops.slack", name = "enabled", havingValue = "true")
    NotificationSender slackSender(SlackProperties props, ObjectProvider<GameCatalog> games) {
        if (props.botToken() == null || props.botToken().isBlank()) {
            throw new IllegalStateException("gameops.slack.enabled=true 인데 bot-token 이 비어 있습니다");
        }
        return new SlackSender(Slack.getInstance().methods(props.botToken()), new SlackMessageBuilder(props, games.getIfAvailable(GameCatalog::empty)));
    }
}
