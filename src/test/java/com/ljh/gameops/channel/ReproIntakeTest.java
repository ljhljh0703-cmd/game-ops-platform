// 디스코드에서 재현 정보 받기 — 빠진 것을 묻고, /report 입력칸 값을 저장하고, 답글로 더 적으면 새 제보가 아니라 원래 제보에 채운다
package com.ljh.gameops.channel;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.discord.DiscordCards;
import com.ljh.gameops.channel.discord.DiscordInbound;
import com.ljh.gameops.channel.discord.DiscordReportHandler;
import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.handoff.HandoffBrief;
import com.ljh.gameops.notify.IssueCardFactory;
import com.ljh.gameops.report.ReproInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ReproIntakeTest extends IntegrationTest {
    @Autowired DiscordReportHandler handler;
    @Autowired IssueCardFactory cards;
    @Autowired FeedbackService feedback;

    private DiscordInbound msg(String id, String content, String attachments) {
        return new DiscordInbound("msg:" + id, "C_REPORT", null, "user-1", false, null, content, attachments, "C_REPORT:" + id);
    }

    @Test
    void 새_제보에_빠진_재현_정보를_묻는다() {
        var r = handler.onChannelMessageReply(msg("1", "v1.2 아이폰에서 보스 웨이브 들어가면 튕겨요", null)).orElseThrow();
        assertThat(r.missing()).containsExactly("재현 순서", "스크린샷");
        var embed = DiscordCards.intake(r, true);
        assertThat(embed.getFields()).anySatisfy(f -> {
            assertThat(f.getName()).isEqualTo("더 알려 주시면 빨리 고쳐요");
            assertThat(f.getValue()).contains("재현 순서 · 스크린샷", "답글");
        });
        assertThat(jdbc.queryForObject("select build_version || '/' || device from report", String.class)).isEqualTo("1.2/아이폰");

        // 같은 제보에 합쳐진 두 번째 사람에게는 묻지 않는다
        var again = handler.onChannelMessageReply(msg("2", "보스 웨이브 들어가면 튕겨요ㅠ", null)).orElseThrow();
        assertThat(again.missing()).isEmpty();
    }

    @Test
    void report_입력칸_값을_저장한다() {
        handler.onSlashCommandReply(new DiscordInbound("cmd:1", "C_ANY", null, "u", false, "gemtower", "결제했는데 보석이 안 들어와요",
                "https://cdn.discordapp.com/attachments/1/2/shot.png", null, ReproInfo.of("1.4.0", "갤럭시 S23", "상점 → 보석 100개 결제")));
        assertThat(jdbc.queryForMap("select build_version, device, repro_steps, attachment_urls from report"))
                .containsEntry("build_version", "1.4.0").containsEntry("device", "갤럭시 S23")
                .containsEntry("repro_steps", "상점 → 보석 100개 결제");
    }

    @Test
    void 답글로_더_적으면_원래_제보에_채우고_새_이슈를_만들지_않는다() {
        var first = handler.onChannelMessageReply(msg("10", "보스 웨이브 들어가면 튕겨요", null)).orElseThrow();
        long before = jdbc.queryForObject("select count(*) from issue", Long.class);

        var added = handler.onReplyMessage(msg("11", "버전 1.3 갤럭시 S23, 3웨이브 끝나고 상점 열면 바로 튕김",
                "https://cdn.discordapp.com/attachments/1/2/crash.png"), "10").orElseThrow();

        assertThat(added.text()).contains("재현 정보를 이슈 #" + first.issueId() + " 에 더했어요");
        assertThat(jdbc.queryForObject("select count(*) from issue", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from report", Long.class)).isEqualTo(1);
        var card = cards.current(first.issueId());
        assertThat(card.repro().line()).isEqualTo("버전 1.3 · 기기 갤럭시 S23 · 재현 순서 있음 · 스크린샷 1장");

        // 개발 지시서에도 재현 정보가 들어간다(없는 칸은 「모름」)
        var brief = HandoffBrief.build("보석타워", "gemtower", 1, 7, "", feedback.recent("gemtower", 7, 50));
        assertThat(brief.body()).contains("- 재현 정보:", "게임 버전: 1.3", "기기: 갤럭시 S23", "재현 순서: 버전 1.3 갤럭시 S23, 3웨이브", "[그림](https://cdn.discordapp.com/");
    }

    @Test
    void 받은_적_없는_글에_단_답글은_보통_제보로() {
        var r = handler.onReplyMessage(msg("20", "상점이 안 열려요 버그", null), "999").orElseThrow();
        assertThat(r.kind()).isEqualTo(DiscordReportHandler.Reply.Kind.NEW);
    }
}
