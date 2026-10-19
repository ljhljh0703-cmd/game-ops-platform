// 알림 전송기 고르기 — 슬랙을 켜면 슬랙 전송기 하나만, 끄면 로그 전송기 하나만 뜬다
package com.ljh.gameops.notify;

import com.ljh.gameops.channel.slack.SlackConfig;
import com.ljh.gameops.channel.slack.SlackProperties;
import com.ljh.gameops.channel.slack.SlackSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SenderSelectionTest {
    @Configuration
    @EnableConfigurationProperties(SlackProperties.class)
    static class Props {}

    // 두 설정 클래스를 읽는 순서를 양쪽으로 바꿔 봐도 결과가 같아야 한다
    private final ApplicationContextRunner forward = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, NotifyConfig.class, SlackConfig.class);
    private final ApplicationContextRunner backward = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, SlackConfig.class, NotifyConfig.class);

    @Test
    void 슬랙을_켜면_슬랙_전송기_하나() {
        for (var runner : new ApplicationContextRunner[]{forward, backward}) {
            runner.withPropertyValues("gameops.slack.enabled=true", "gameops.slack.bot-token=xoxb-test")
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(NotificationSender.class);
                        assertThat(ctx.getBean(NotificationSender.class)).isInstanceOf(SlackSender.class);
                    });
        }
    }

    @Test
    void 슬랙을_끄면_로그_전송기_하나() {
        for (var runner : new ApplicationContextRunner[]{forward, backward}) {
            runner.withPropertyValues("gameops.slack.enabled=false").run(ctx -> {
                assertThat(ctx).hasSingleBean(NotificationSender.class);
                assertThat(ctx.getBean(NotificationSender.class)).isInstanceOf(LoggingSender.class);
            });
        }
    }
}
