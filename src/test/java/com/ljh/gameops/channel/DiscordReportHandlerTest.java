// 디스코드 제보 처리 — 제보 채널 글 접수, 중복 수신 1건, 봇·다른 채널 무시, /report 명령
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
        return new DiscordInbound("msg:" + id, channel, "user-1", bot, null, content, null);
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
        String r = handler.onSlashCommand(new DiscordInbound("cmd:9", "C_ANY", "user-2", false,
                "jelly-panic", "젤리가 안 터져요 버그", null));
        assertThat(r).contains("접수했어요");
        assertThat(jdbc.queryForObject("select g.code from report r join game g on g.id = r.game_id", String.class))
                .isEqualTo("jelly-panic");
    }

    @Test
    void 없는_게임을_고르면_안내한다() {
        String r = handler.onSlashCommand(new DiscordInbound("cmd:9", "C_ANY", "user-2", false, "nope", "버그", null));
        assertThat(r).contains("등록되지 않은 게임");
        assertThat(reports()).isZero();
    }
}
