// 디스코드 답장 카드 모양과 해결 답장 대상 고르기(같은 사람 한 번, 답장 위치 없는 제보 제외)
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.report.Report;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordCardsTest {
    @Test
    void 접수_카드는_심각도_색과_분류_칸_그리고_답장_약속() {
        var r = new DiscordReportHandler.Reply("[보석타워] 접수했어요. 이슈 #12 로 개발팀에 전달합니다.", DiscordReportHandler.Reply.Kind.NEW,
                12L, "보석타워", Category.CRASH, Severity.HIGH, 1);
        var e = DiscordCards.intake(r, true);
        assertThat(e.getTitle()).isEqualTo("📨 접수했어요 · 이슈 #12");
        assertThat(e.getColorRaw()).isEqualTo(0xE8912D);
        assertThat(e.getFields()).extracting(f -> f.getName() + "=" + f.getValue()).containsExactly("분류=크래시", "심각도=높음", "같은 제보=1건");
        assertThat(e.getFooter().getText()).contains("고치면 이 글에 답장");
        // 명령(/report)으로 받은 건 나중에 답장할 수 없어 약속하지 않는다
        assertThat(DiscordCards.intake(r, false).getFooter().getText()).doesNotContain("답장");
    }

    @Test
    void 오류_카드는_빨강() {
        var e = DiscordCards.intake(new DiscordReportHandler.Reply("등록되지 않은 게임이에요: x", DiscordReportHandler.Reply.Kind.ERROR,
                null, null, null, null, 0), true);
        assertThat(e.getColorRaw()).isEqualTo(DiscordCards.RED);
        assertThat(e.getFields()).isEmpty();
    }

    @Test
    void 해결_카드는_초록() {
        var e = DiscordCards.resolved("보석타워", 12, "보스 웨이브에서 튕겨요");
        assertThat(e.getColorRaw()).isEqualTo(DiscordCards.GREEN);
        assertThat(e.getTitle()).isEqualTo("✅ 고쳤어요 · 이슈 #12");
        assertThat(e.getDescription()).contains("[보석타워] 「보스 웨이브에서 튕겨요」");
    }

    @Test
    void 해결_답장은_같은_사람에게_한_번만_답장_위치_없는_제보는_빼고() {
        var a1 = new Report(1L, SourceChannel.DISCORD, "msg:1", "author-a", "튕겨요", null, "C1:1");
        var a2 = new Report(1L, SourceChannel.DISCORD, "msg:2", "author-a", "또 튕겨요", null, "C1:2");
        var b = new Report(1L, SourceChannel.DISCORD, "msg:3", "author-b", "저도요", null, "T9:3");
        var api = new Report(1L, SourceChannel.API, "x", "author-c", "API 제보", null, null);
        var broken = new Report(1L, SourceChannel.DISCORD, "msg:4", "author-d", "?", null, "깨진값");
        assertThat(DiscordPlayerNotifier.targets(List.of(a1, a2, b, api, broken)))
                .extracting(t -> t.channelId() + ":" + t.messageId()).containsExactly("C1:1", "T9:3");
    }
}
