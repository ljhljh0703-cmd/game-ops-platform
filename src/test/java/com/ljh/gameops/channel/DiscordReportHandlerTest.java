// 디스코드 제보 처리 — 제보 채널 글 접수, 중복 수신 1건, 봇·다른 채널 무시, /report 명령, 게임별 채널
package com.ljh.gameops.channel;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.discord.DiscordInbound;
import com.ljh.gameops.channel.discord.DiscordReportHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordReportHandlerTest extends IntegrationTest {
    @Autowired DiscordReportHandler handler;

    private DiscordInbound msg(String id, String channel, boolean bot, String content) {
        return new DiscordInbound("msg:" + id, channel, null, "user-1", bot, null, content, null);
    }

    private long reports() {
        return jdbc.queryForObject("select count(*) from report", Long.class);
    }

    @Test
    void 제보_채널_글은_접수하고_이슈_번호로_답한다() {
        var reply = handler.onChannelMessage(msg("1", "C_REPORT", false, "보스 웨이브에서 튕겨요"));
        assertThat(reply).hasValueSatisfying(r -> assertThat(r).contains("이슈 #1"));
        assertThat(reports()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select source_channel from report", String.class)).isEqualTo("DISCORD");
    }

    @Test
    void 같은_디스코드_메시지를_두_번_받아도_제보는_1건() {
        handler.onChannelMessage(msg("1", "C_REPORT", false, "보스 웨이브에서 튕겨요"));
        var again = handler.onChannelMessage(msg("1", "C_REPORT", false, "보스 웨이브에서 튕겨요"));
        assertThat(again).hasValueSatisfying(r -> assertThat(r).contains("이미 접수된"));
        assertThat(reports()).isEqualTo(1);
    }

    @Test
    void 비슷한_제보는_합쳤다고_알려_준다() {
        handler.onChannelMessage(msg("1", "C_REPORT", false, "보스 웨이브에서 튕겨요"));
        var r = handler.onChannelMessage(msg("2", "C_REPORT", false, "보스웨이브에서 튕겨요ㅠ"));
        assertThat(r).hasValueSatisfying(t -> assertThat(t).contains("합쳤어요").contains("2건째"));
    }

    @Test
    void 봇_글과_다른_채널_글은_무시한다() {
        assertThat(handler.onChannelMessage(msg("1", "C_REPORT", true, "봇이 쓴 글"))).isEmpty();
        assertThat(handler.onChannelMessage(msg("2", "C_CHAT", false, "잡담 채널 글"))).isEmpty();
        assertThat(reports()).isZero();
    }

    @Test
    void report_명령은_게임을_고를_수_있다() {
        String r = handler.onSlashCommand(new DiscordInbound("cmd:9", "C_ANY", "잡담", "user-2", false,
                "jelly-panic", "젤리가 안 터져요 버그", null));
        assertThat(r).contains("접수했어요");
        assertThat(jdbc.queryForObject("select g.code from report r join game g on g.id = r.game_id", String.class))
                .isEqualTo("jelly-panic");
    }

    @Test
    void 없는_게임을_고르면_안내한다() {
        String r = handler.onSlashCommand(new DiscordInbound("cmd:9", "C_ANY", "잡담", "user-2", false, "nope", "버그", null));
        assertThat(r).contains("등록되지 않은 게임");
        assertThat(reports()).isZero();
    }

    private String gameOfOnlyReport() {
        return jdbc.queryForObject("select g.code from report r join game g on g.id = r.game_id", String.class);
    }

    @Test
    void 게임별_채널_이름으로_어느_게임_제보인지_정한다() {
        var r = handler.onChannelMessage(new DiscordInbound("msg:m1", "999", "무브퓨-버그제보", "u", false, null, "점프하면 튕겨요", null));
        assertThat(r).hasValueSatisfying(t -> assertThat(t).startsWith("[무브퓨]").contains("이슈 #1"));
        assertThat(gameOfOnlyReport()).isEqualTo("movefu");
    }

    @Test
    void 채널_이름은_대소문자와_샵을_가리지_않는다() {
        handler.onChannelMessage(new DiscordInbound("msg:m2", "998", "#젤리패닉-버그제보", "u", false, null, "젤리가 안 터져요", null));
        assertThat(gameOfOnlyReport()).isEqualTo("jelly-panic");
    }

    @Test
    void 같은_글이라도_게임이_다르면_따로_묶인다() {
        handler.onChannelMessage(new DiscordInbound("msg:a", "1", "보석타워-버그제보", "u", false, null, "로딩에서 멈춰요", null));
        var r = handler.onChannelMessage(new DiscordInbound("msg:b", "2", "젤리패닉-버그제보", "u", false, null, "로딩에서 멈춰요", null));
        assertThat(r).hasValueSatisfying(t -> assertThat(t).contains("이슈 #2").doesNotContain("합쳤어요"));
    }

    @Test
    void report_명령을_게임_채널에서_쓰면_그_채널의_게임() {
        handler.onSlashCommand(new DiscordInbound("cmd:10", "997", "보석타워-버그제보", "u", false, null, "결제가 두 번 됐어요", null));
        assertThat(gameOfOnlyReport()).isEqualTo("gemtower");
    }

    @Test
    void 게임별_채널이_아닌_곳의_글은_받지_않는다() {
        assertThat(handler.onChannelMessage(new DiscordInbound("msg:z", "996", "자유-채팅", "u", false, null, "튕겨요", null))).isEmpty();
        assertThat(reports()).isZero();
    }
}
