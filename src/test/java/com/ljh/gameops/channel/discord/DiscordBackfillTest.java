// 놓친 제보 받기 — 이미 받은 글·그 글에 이어 쓴 줄·봇 글은 건너뛰고, 새로 이어 쓴 줄은 하나로, 답글은 따로
package com.ljh.gameops.channel.discord;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordBackfillTest {
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-27T10:00:00+09:00");

    static DiscordBackfill.Msg m(String id, String who, int sec, String text) {
        return new DiscordBackfill.Msg(id, who, false, text, null, T0.plusSeconds(sec), null, List.of());
    }

    @Test
    void 받은_글과_그_이어쓴_줄은_건너뛰고_새_글만() {
        var msgs = List.of(
                m("1", "a", 0, "보스에서"), m("2", "a", 10, "튕겨요"),          // 1 은 받았음, 2 는 이어 쓴 줄
                new DiscordBackfill.Msg("3", "bot", true, "접수했어요", null, T0.plusSeconds(12), null, List.of()),
                m("4", "b", 300, "상점이 안 열려요"), m("5", "b", 310, "아이폰임"),   // 놓친 글, 이어 쓴 줄
                m("6", "a", 400, "결제했는데 보석이 안 와요"));                     // 놓친 글(다른 묶음)
        var items = DiscordBackfill.plan(msgs, "C", "보석타워-버그-제보", Set.of("msg:1")::contains);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).in().content()).isEqualTo("상점이 안 열려요\n아이폰임");
        assertThat(items.get(0).firstMessageId()).isEqualTo("4");
        assertThat(items.get(0).in().externalId()).isEqualTo("msg:4");
        assertThat(items.get(0).in().replyTo()).isEqualTo("C:4");
        assertThat(items.get(1).in().content()).isEqualTo("결제했는데 보석이 안 와요");
    }

    @Test
    void 시간이_벌어지면_따로_받는다() {
        var items = DiscordBackfill.plan(List.of(m("1", "a", 0, "버그 하나"), m("2", "a", 90, "버그 둘")), "C", null, x -> false);
        assertThat(items).hasSize(2);
    }

    @Test
    void 답글은_재현_정보로_따로() {
        var reply = new DiscordBackfill.Msg("9", "a", false, "버전 1.2 아이폰", null, T0, "1", List.of());
        var items = DiscordBackfill.plan(List.of(reply), "C", null, x -> false);
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.isReply()).isTrue();
            assertThat(i.originalId()).isEqualTo("1");
        });
    }

    @Test
    void 글_없이_그림만이면_받지_않는다() {
        var pic = new DiscordBackfill.Msg("1", "a", false, "", "https://cdn.discordapp.com/x.png", T0, null, List.of());
        assertThat(DiscordBackfill.plan(List.of(pic), "C", null, x -> false)).isEmpty();
    }
}
