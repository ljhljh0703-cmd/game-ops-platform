// 슬랙 메시지 — 심각도별 채널 분기, 상태 버튼 값
package com.ljh.gameops.channel;

import com.ljh.gameops.channel.slack.SlackMessageBuilder;
import com.ljh.gameops.channel.slack.SlackProperties;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.RenderedNotification;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.element.ButtonElement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackMessageBuilderTest {
    private final SlackMessageBuilder b = new SlackMessageBuilder(
            new SlackProperties(true, "x", "s", new SlackProperties.Channels("C_DEFAULT", "C_URGENT")));

    private RenderedNotification n(Severity s, Long issueId) {
        return new RenderedNotification(NotificationType.NEW_ISSUE, s, issueId, "text");
    }

    @Test
    void 높은_심각도는_긴급_채널() {
        assertThat(b.channelFor(n(Severity.CRITICAL, 1L))).isEqualTo("C_URGENT");
        assertThat(b.channelFor(n(Severity.HIGH, 1L))).isEqualTo("C_URGENT");
        assertThat(b.channelFor(n(Severity.MEDIUM, 1L))).isEqualTo("C_DEFAULT");
    }

    @Test
    void 긴급_채널이_없으면_기본_채널로() {
        var noUrgent = new SlackMessageBuilder(new SlackProperties(true, "x", "s", new SlackProperties.Channels("C_DEFAULT", "")));
        assertThat(noUrgent.channelFor(n(Severity.CRITICAL, 1L))).isEqualTo("C_DEFAULT");
    }

    @Test
    void 기본_채널이_없으면_보내지_않는다() {
        var none = new SlackMessageBuilder(new SlackProperties(true, "x", "s", null));
        assertThatThrownBy(() -> none.channelFor(n(Severity.LOW, 1L))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이슈_알림에는_상태_버튼_세_개() {
        var blocks = b.blocks(n(Severity.LOW, 7L));
        assertThat(blocks).hasSize(2);
        var actions = (ActionsBlock) blocks.get(1);
        assertThat(actions.getElements()).extracting(e -> ((ButtonElement) e).getValue())
                .containsExactly("7:IN_PROGRESS", "7:RESOLVED", "7:WONT_FIX");
    }

    @Test
    void 주간_보고에는_버튼이_없다() {
        assertThat(b.blocks(new RenderedNotification(NotificationType.WEEKLY_REPORT, Severity.LOW, null, "주간"))).hasSize(1);
    }
}
