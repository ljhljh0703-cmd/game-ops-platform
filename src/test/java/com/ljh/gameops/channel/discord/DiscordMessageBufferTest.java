// 끊어 쓴 글 합치기(결정 44) — 같은 사람·같은 채널의 이어 쓴 줄은 제보 하나로, 다른 사람·다른 채널은 따로
package com.ljh.gameops.channel.discord;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DiscordMessageBufferTest {
    final List<DiscordInbound> out = new CopyOnWriteArrayList<>();

    static DiscordInbound msg(String id, String ch, String author, String text, String file) {
        return new DiscordInbound("msg:" + id, ch, null, author, false, null, text, file, ch + ":" + id);
    }

    @Test
    void 이어_쓴_줄은_하나로_합쳐_처음_글_번호로() {
        var b = new DiscordMessageBuffer(300, 5000, out::add);
        b.add(msg("1", "C", "u1", "보스에서", null));
        b.add(msg("2", "C", "u1", "튕겨요", "https://cdn.discordapp.com/a.png"));
        b.add(msg("3", "C", "u1", "아이폰임", null));
        b.add(msg("4", "C", "u2", "저도 튕겨요", null)); // 다른 사람
        await().until(() -> out.size() == 2);
        var u1 = out.stream().filter(i -> i.authorId().equals("u1")).findFirst().orElseThrow();
        assertThat(u1.content()).isEqualTo("보스에서\n튕겨요\n아이폰임");
        assertThat(u1.externalId()).isEqualTo("msg:1");
        assertThat(u1.replyTo()).isEqualTo("C:1");
        assertThat(u1.attachmentUrls()).isEqualTo("https://cdn.discordapp.com/a.png");
    }

    @Test
    void 처음_글부터_최대_시간이_지나면_넘긴다() {
        var b = new DiscordMessageBuffer(10_000, 400, out::add);
        b.add(msg("1", "C", "u1", "계속", null));
        b.add(msg("2", "C", "u1", "쓰는 중", null));
        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> out.size() == 1);
        assertThat(out.get(0).content()).isEqualTo("계속\n쓰는 중");
    }

    @Test
    void 서버를_끌_때는_모은_것을_바로_넘긴다() {
        var b = new DiscordMessageBuffer(60_000, 60_000, out::add);
        b.add(msg("1", "C", "u1", "끄기 직전 제보", null));
        b.flushAll();
        assertThat(out).hasSize(1);
    }

    @Test
    void 열_줄이_넘으면_나눠서_넘긴다() {
        var b = new DiscordMessageBuffer(60_000, 60_000, out::add);
        for (int i = 0; i < 11; i++) b.add(msg(String.valueOf(i), "C", "u1", "줄" + i, null));
        assertThat(out).hasSize(1);
        b.flushAll();
        assertThat(out).hasSize(2);
    }
}
