// 슬랙 앱 홈 현황판 모양 — 봇을 누르면 보이는 화면. 게임마다 열림·진행 중·긴급·이번 주 해결 수와 먼저 볼 이슈 5개,
// 맨 아래 최근 AI 피드백 정리와 최근 규칙 반영. 외부 호출 없이 모양만 만든다(시험 대상)
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.issue.Labels;
import com.ljh.gameops.issue.Severity;
import com.slack.api.model.block.*;
import com.slack.api.model.block.composition.TextObject;
import com.slack.api.model.view.View;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.element.BlockElements.button;

public final class SlackHome {
    public static final String ACTION_REFRESH = "home_refresh";
    static final int TOP = 5;

    public record IssueRow(long id, String title, Category category, Severity severity, IssueStatus status, int reportCount) {}

    public record GameBoard(String name, long open, long inProgress, long urgent, long resolvedWeek, List<IssueRow> top) {}

    /** @param lastDigest 「10/21 05:40 · 보석타워」 처럼, 없으면 null. @param lastRules 「#3 · 10/21 05:41 · 불편/낮음 + …」, 없으면 null */
    public record Board(List<GameBoard> games, String lastDigest, String lastRules, OffsetDateTime at) {}

    private SlackHome() {}

    public static View view(Board board) {
        List<LayoutBlock> b = new ArrayList<>();
        b.add(HeaderBlock.builder().text(SlackCards.plain("🎮 게임 운영 현황")).build());
        b.add(ActionsBlock.builder().blockId("home_top").elements(List.of(button(x -> x.actionId(ACTION_REFRESH)
                .text(SlackCards.plain("🔄 새로고침")).value("refresh")))).build());
        b.add(ContextBlock.builder().elements(List.of(SlackCards.md("업데이트 " + SlackCards.when(board.at())
                + " · 게임 채널 카드에서 바로 처리할 수 있어요"))).build());
        for (GameBoard g : board.games()) {
            b.add(DividerBlock.builder().build());
            b.add(SectionBlock.builder().text(SlackCards.md("*" + SlackCards.escape(g.name()) + "*")).fields(List.<TextObject>of(
                    SlackCards.md("*열림*\n" + g.open() + "건"),
                    SlackCards.md("*진행 중*\n" + g.inProgress() + "건"),
                    SlackCards.md("*긴급·높음*\n" + (g.urgent() > 0 ? "🔴 " : "") + g.urgent() + "건"),
                    SlackCards.md("*이번 주 해결*\n" + g.resolvedWeek() + "건"))).build());
            b.add(SectionBlock.builder().text(SlackCards.md(rows(g.top()))).build());
        }
        b.add(DividerBlock.builder().build());
        List<ContextBlockElement> foot = new ArrayList<>();
        foot.add(SlackCards.md("🧠 최근 피드백 정리 · " + (board.lastDigest() == null ? "아직 없음(Claude 에 「피드백 정리해」)" : SlackCards.escape(board.lastDigest()))));
        foot.add(SlackCards.md("🤖 최근 규칙 반영 · " + (board.lastRules() == null ? "아직 없음" : SlackCards.escape(board.lastRules()))));
        b.add(ContextBlock.builder().elements(foot).build());
        return View.builder().type("home").blocks(b).build();
    }

    static String rows(List<IssueRow> top) {
        if (top.isEmpty()) return "_열린 이슈가 없어요_ ✅";
        StringBuilder s = new StringBuilder("*먼저 볼 이슈*\n");
        for (IssueRow r : top) {
            s.append(mark(r.severity())).append(" #").append(r.id()).append(' ').append(SlackCards.escape(SlackCards.cut(r.title(), 40)))
                    .append(" · ").append(Labels.CATEGORY.get(r.category()))
                    .append(" · 제보 ").append(r.reportCount()).append("건");
            if (r.status() == IssueStatus.IN_PROGRESS) s.append(" · 🛠️ 진행 중");
            s.append('\n');
        }
        return s.toString().stripTrailing();
    }

    static String mark(Severity s) {
        return switch (s) {
            case CRITICAL -> "🔴";
            case HIGH -> "🟠";
            case MEDIUM -> "🟡";
            case LOW -> "⚪";
        };
    }
}
