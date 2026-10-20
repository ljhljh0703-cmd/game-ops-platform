// 슬랙이 켜져 있으면 슬랙 전송기를 알림 전송기로 쓰고, 버튼·입력 창용 Web API 도 같은 봇 토큰으로 만든다
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

    @Bean
    SlackWebApi slackWebApi(SlackProperties props) {
        if (props.enabled() && props.botToken() != null && !props.botToken().isBlank()) {
            return new MethodsSlackWebApi(Slack.getInstance().methods(props.botToken()));
        }
        // 슬랙을 끈 채로 켰을 때: 버튼 요청이 와도 상태 변경은 되고, 카드·입력 창 호출만 건너뛴다
        return new SlackWebApi() {
            public boolean openView(String triggerId, com.slack.api.model.view.View view) { return false; }
            public void updateCard(String channel, String ts, com.ljh.gameops.notify.IssueCard card) {}
        };
    }
}
