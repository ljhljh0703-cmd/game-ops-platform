// 슬랙 이슈 카드 모양 — 색 띠·4칸·원문 인용·버튼이 상태에 맞게 나오고, 플레이어 글의 서식 문자는 무력화된다
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.IssueCard;
import com.ljh.gameops.report.SourceChannel;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.element.ButtonElement;
import com.slack.api.util.json.GsonFactory;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlackCardsTest {
    private static IssueCard card(Severity sev, IssueStatus status, int reports, String body) {
        return new IssueCard(12, "보석타워", "새 이슈", "보스 웨이브에서 튕겨요", body, Category.CRASH, sev, status, reports,
                3L, null, SourceChannel.DISCORD, OffsetDateTime.of(2026, 10, 20, 13, 31, 0, 0, ZoneOffset.UTC), null);
    }

    private static List<String> buttons(List<com.slack.api.model.block.LayoutBlock> blocks) {
        return blocks.stream().filter(b -> b instanceof ActionsBlock)
                .flatMap(b -> ((ActionsBlock) b).getElements().stream())
                .map(e -> ((ButtonElement) e).getText().getText()).toList();
    }

    @Test
    void 열린_이슈_카드는_문제_제목_한눈_요약_다음_할_일_인용_맥락_버튼() {
        var a = SlackCards.card(card(Severity.HIGH, IssueStatus.OPEN, 3, "보스 웨이브에서 튕겨요ㅠ"), null);
        assertThat(a.getColor()).isEqualTo("#E8912D");
        var blocks = a.getBlocks();
        // 제목 줄은 「무슨 문제인지」(결정 48)
        assertThat(((HeaderBlock) blocks.get(0)).getText().getText()).isEqualTo("🟠 보석타워 · 보스 웨이브에서 튕겨요");
        String summary = ((SectionBlock) blocks.get(1)).getText().getText();
        assertThat(summary).startsWith("🆕 새 이슈 #12 · *크래시 · 높음* · 👥 제보 3건 · 지금 *열림*")
                .contains("👉 재현 정보(버전·기기)가 아직 없어요");
        assertThat(((SectionBlock) blocks.get(2)).getText().getText()).isEqualTo("> 보스 웨이브에서 튕겨요ㅠ");
        // 디스코드 · 처음 시각은 서울 시간(UTC 13:31 → 22:31)
        String json = GsonFactory.createSnakeCase().toJson(a);
        assertThat(json).contains("재발 의심").contains("#3").contains("디스코드 · 처음 10/20 22:31");
        assertThat(buttons(blocks)).containsExactly("🛠 고치는 중", "✅ 해결", "⏸ 보류", "🏷️ 분류 고치기", "✂️ 잘못 묶임 떼어내기");
    }

    @Test
    void 블록은_다섯_개라_슬랙이_접지_않는다() {
        assertThat(SlackCards.card(card(Severity.HIGH, IssueStatus.OPEN, 3, "x"), "_(공용 채널로 왔어요)_").getBlocks()).hasSize(5);
    }

    @Test
    void 심각도별_색() {
        assertThat(SlackCards.card(card(Severity.CRITICAL, IssueStatus.OPEN, 1, "x"), null).getColor()).isEqualTo("#E01E5A");
        assertThat(SlackCards.card(card(Severity.MEDIUM, IssueStatus.OPEN, 1, "x"), null).getColor()).isEqualTo("#ECB22E");
        assertThat(SlackCards.card(card(Severity.LOW, IssueStatus.OPEN, 1, "x"), null).getColor()).isEqualTo("#A0A0A0");
    }

    @Test
    void 제보가_하나뿐이면_떼어내기_버튼이_없다() {
        assertThat(buttons(SlackCards.card(card(Severity.HIGH, IssueStatus.OPEN, 1, "x"), null).getBlocks()))
                .doesNotContain("✂️ 잘못 묶임 떼어내기").contains("🏷️ 분류 고치기");
    }

    @Test
    void 해결한_카드는_초록_띠에_다시_열기_하나와_누가_언제() {
        var c = card(Severity.HIGH, IssueStatus.RESOLVED, 3, "x").withLastAction("✅ 해결됨 · <@U1> · 10/20 22:40");
        var a = SlackCards.card(c, null);
        assertThat(a.getColor()).isEqualTo("#2EB67D");
        assertThat(((HeaderBlock) a.getBlocks().get(0)).getText().getText()).startsWith("✅ ");
        assertThat(buttons(a.getBlocks())).containsExactly("↩️ 다시 열기");
        assertThat(a.getBlocks()).filteredOn(b -> b instanceof com.slack.api.model.block.ContextBlock)
                .flatExtracting(b -> ((com.slack.api.model.block.ContextBlock) b).getElements())
                .extracting(e -> ((com.slack.api.model.block.composition.MarkdownTextObject) e).getText())
                .contains("*✅ 해결됨 · <@U1> · 10/20 22:40*");
        assertThat(a.getBlocks()).hasSize(5); // 6개를 넘으면 슬랙이 「더 보기」로 접는다
    }

    @Test
    void 진행_중이면_진행_중_버튼은_빠진다() {
        assertThat(buttons(SlackCards.card(card(Severity.HIGH, IssueStatus.IN_PROGRESS, 2, "x"), null).getBlocks()))
                .containsExactly("✅ 해결", "⏸ 보류", "🏷️ 분류 고치기", "✂️ 잘못 묶임 떼어내기");
    }

    @Test
    void 플레이어_글의_전체_호출과_가짜_링크는_동작하지_않는다() {
        var a = SlackCards.card(card(Severity.LOW, IssueStatus.OPEN, 1, "<!channel> 여기 <https://evil.example|보상 받기> & 줄\n둘째 줄"), null);
        String quote = ((SectionBlock) a.getBlocks().get(2)).getText().getText();
        assertThat(quote).doesNotContain("<!channel>").doesNotContain("<https://")
                .contains("&lt;!channel&gt;").contains("&amp;")
                .isEqualTo("> &lt;!channel&gt; 여기 &lt;https://evil.example|보상 받기&gt; &amp; 줄\n> 둘째 줄");
    }

    @Test
    void 긴_원문은_줄인다() {
        String quote = SlackCards.quote("가".repeat(900));
        assertThat(quote.length()).isLessThan(520);
        assertThat(quote).endsWith("…");
    }

    @Test
    void 분류_고치기_창은_지금_값이_처음_선택() {
        var v = SlackCards.reclassifyView(card(Severity.HIGH, IssueStatus.OPEN, 2, "x"), "12|C1|111.222");
        String json = GsonFactory.createSnakeCase().toJson(v);
        assertThat(v.getCallbackId()).isEqualTo("reclassify");
        assertThat(v.getPrivateMetadata()).isEqualTo("12|C1|111.222");
        assertThat(json).contains("\"initial_option\":{\"text\":{\"type\":\"plain_text\",\"text\":\"크래시\"");
        assertThat(v.getTitle().getText().length()).isLessThanOrEqualTo(24);
    }

    @Test
    void 떼어내기_창은_묶인_제보를_고르게_한다() {
        var v = SlackCards.detachView(card(Severity.HIGH, IssueStatus.OPEN, 2, "x"),
                List.of(new SlackCards.ReportChoice(5, "스테이지 3에서 게임이 느려요", OffsetDateTime.now()),
                        new SlackCards.ReportChoice(4, "스테이지 3에서 게임이 튕겨요", OffsetDateTime.now())), "12||");
        String json = GsonFactory.createSnakeCase().toJson(v);
        assertThat(v.getCallbackId()).isEqualTo("detach");
        assertThat(json).contains("스테이지 3에서 게임이 느려요").contains("\"value\":\"5\"").contains("radio_buttons");
    }

    @Test
    void QA_확인_대기_카드는_파란_띠에_확인_완료와_다시_열기() {
        var a = SlackCards.card(card(Severity.HIGH, IssueStatus.VERIFYING, 2, "튕겨요"), null);
        assertThat(a.getColor()).isEqualTo("#36C5F0");
        assertThat(((HeaderBlock) a.getBlocks().get(0)).getText().getText()).startsWith("🧪");
        assertThat(buttons(a.getBlocks())).containsExactly("✅ QA 확인 완료", "↩️ 아직 나요 · 다시 열기");
    }

    @Test
    void 재현_정보와_Jira_링크가_맥락_줄에() {
        var c = card(Severity.HIGH, IssueStatus.OPEN, 2, "튕겨요");
        var withRepro = new IssueCard(c.issueId(), c.gameName(), c.label(), c.title(), c.quote(), c.category(), c.severity(), c.status(),
                c.reportCount(), null, null, c.source(), c.createdAt(), null,
                com.ljh.gameops.report.ReproInfo.Summary.from(List.of(new com.ljh.gameops.report.ReproInfo.Summary.Part("1.2", "<!channel>", null, null))),
                "GEM-7", "https://studio.atlassian.net/browse/GEM-7");
        String text = SlackCards.card(withRepro, null).getBlocks().toString();
        assertThat(text).contains("🧪 버전 1.2 · 기기 &lt;!channel&gt; · 재현 순서 없음", "🎫 Jira <https://studio.atlassian.net/browse/GEM-7|GEM-7>");
        assertThat(SlackCards.card(c, null).getBlocks().toString()).contains("🧪 재현 정보 없음");
    }
}
