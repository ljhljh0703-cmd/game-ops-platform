// 슬랙 메시지 — 게임별·심각도별 채널 분기, 상태 버튼 값
package com.ljh.gameops.channel;

import com.ljh.gameops.channel.slack.SlackMessageBuilder;
import com.ljh.gameops.channel.slack.SlackProperties;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.issue.Severity;

import java.util.List;
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
        return new RenderedNotification(NotificationType.NEW_ISSUE, null, s, issueId, "text");
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
        assertThat(b.blocks(new RenderedNotification(NotificationType.WEEKLY_REPORT, null, Severity.LOW, null, "주간"))).hasSize(1);
    }

    // 젤리 패닉은 자기 채널만, 보석타워는 자기 긴급 채널까지, 무브퓨는 목록에 슬랙 채널이 없다
    private final SlackMessageBuilder perGame = new SlackMessageBuilder(
            new SlackProperties(true, "x", "s", new SlackProperties.Channels("C_DEFAULT", "C_URGENT")),
            new GameCatalog(List.of(
                    new GameSpec("jelly-panic", "젤리 패닉", List.of(), "#젤리패닉-알림", null),
                    new GameSpec("gemtower", "보석타워", List.of(), "#보석타워-알림", "#보석타워-긴급"),
                    new GameSpec("movefu", "무브퓨", List.of(), null, null))));

    private RenderedNotification g(String game, Severity s) {
        return new RenderedNotification(NotificationType.NEW_ISSUE, game, s, 1L, "text");
    }

    @Test
    void 게임마다_자기_슬랙_채널로_보낸다() {
        assertThat(perGame.channelFor(g("jelly-panic", Severity.LOW))).isEqualTo("#젤리패닉-알림");
        assertThat(perGame.channelFor(g("gemtower", Severity.MEDIUM))).isEqualTo("#보석타워-알림");
    }

    @Test
    void 심각하면_게임_긴급_채널_없으면_공용_긴급_채널() {
        assertThat(perGame.channelFor(g("gemtower", Severity.CRITICAL))).isEqualTo("#보석타워-긴급");
        assertThat(perGame.channelFor(g("jelly-panic", Severity.HIGH))).isEqualTo("C_URGENT");
    }

    @Test
    void 슬랙_채널이_없는_게임과_모르는_게임은_공용_채널로() {
        assertThat(perGame.channelFor(g("movefu", Severity.LOW))).isEqualTo("C_DEFAULT");
        assertThat(perGame.channelFor(g("unknown", Severity.LOW))).isEqualTo("C_DEFAULT");
        assertThat(perGame.channelFor(g(null, Severity.LOW))).isEqualTo("C_DEFAULT");
    }

    @Test
    void 주간_보고도_게임_채널로() {
        var weekly = new RenderedNotification(NotificationType.WEEKLY_REPORT, "gemtower", Severity.LOW, null, "주간");
        assertThat(perGame.channelFor(weekly)).isEqualTo("#보석타워-알림");
    }

    @Test
    void 봇_얼굴은_켤_때만_게임별_없으면_공용() {
        var games = new GameCatalog(List.of(
                new GameSpec("gemtower", "보석타워", List.of(), "#보석타워-알림", null, "보석타워 운영봇", "https://x/gem.png"),
                new GameSpec("movefu", "무브퓨", List.of(), null, null)));
        var ch = new SlackProperties.Channels("C_DEFAULT", "C_URGENT");
        var on = new SlackMessageBuilder(new SlackProperties(true, "x", "s", ch, new SlackProperties.Identity(true, "게임 운영 봇", "https://x/bot.png")), games);
        var gem = new RenderedNotification(NotificationType.NEW_ISSUE, "gemtower", Severity.LOW, 1L, "t");
        var mf = new RenderedNotification(NotificationType.NEW_ISSUE, "movefu", Severity.LOW, 1L, "t");
        assertThat(on.identityFor(gem)).containsExactly("보석타워 운영봇", "https://x/gem.png");
        assertThat(on.identityFor(mf)).containsExactly("게임 운영 봇", "https://x/bot.png");
        var off = new SlackMessageBuilder(new SlackProperties(true, "x", "s", ch, new SlackProperties.Identity(false, "게임 운영 봇", "https://x/bot.png")), games);
        assertThat(off.identityFor(gem)).isNull();
        assertThat(b.identityFor(gem)).isNull(); // 설정 없음
    }
}
