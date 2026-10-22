// 플레이어 말로 답하기(결정 42) — 영어·일본어 제보에는 그 말로, 중국어·기타는 영어로. 슬랙(운영자)은 한국어 그대로
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerTextTest {
    private DiscordReportHandler.Reply reply(String lang) {
        return new DiscordReportHandler.Reply("[Gem Tower] " + PlayerText.received(lang, 12), DiscordReportHandler.Reply.Kind.NEW,
                12L, "Gem Tower", Category.CRASH, Severity.HIGH, 1, List.of("게임 버전", "스크린샷"), lang);
    }

    @Test
    void 영어_제보에는_영어_카드() {
        var e = DiscordCards.intake(reply("en"), true);
        assertThat(e.getTitle()).isEqualTo("📨 Received · Issue #12");
        assertThat(e.getDescription()).contains("Sent to the dev team as issue #12");
        assertThat(e.getFields()).extracting(f -> f.getName() + "=" + f.getValue())
                .contains("Type=Crash", "Severity=High", "Same reports=1");
        assertThat(e.getFields().get(3).getValue()).contains("game version · screenshot").contains("reply to this message");
        assertThat(e.getFooter().getText()).contains("We'll reply here when it's fixed");
    }

    @Test
    void 일본어_제보에는_일본어_카드() {
        var e = DiscordCards.intake(reply("ja"), true);
        assertThat(e.getTitle()).isEqualTo("📨 受付 · 課題 #12");
        assertThat(e.getFields().get(0).getValue()).isEqualTo("クラッシュ");
        assertThat(e.getFields().get(3).getValue()).contains("ゲームのバージョン");
    }

    @Test
    void 중국어와_기타_언어는_영어로_답한다() {
        assertThat(PlayerText.of("zh")).isEqualTo("en");
        assertThat(PlayerText.of("other")).isEqualTo("en");
        assertThat(PlayerText.of(null)).isEqualTo("ko");
    }

    @Test
    void 해결_답장도_플레이어_말로_자기_글을_보인다() {
        var e = DiscordCards.resolved("Gem Tower", 12, "The game crashes every time I reach wave 5", "en");
        assertThat(e.getTitle()).isEqualTo("✅ Fixed · Issue #12");
        assertThat(e.getDescription()).contains("The game crashes").contains("Thanks for reporting");
    }
}
