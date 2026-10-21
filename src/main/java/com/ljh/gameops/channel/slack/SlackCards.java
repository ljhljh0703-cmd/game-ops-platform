// 슬랙 이슈 카드와 입력 창 모양 — 외부 호출 없이 모양만 만든다(시험 대상).
// 카드: 심각도 색 띠 · 제목 · 4칸(분류·심각도·제보·상태) · 플레이어 원문 인용 · 맥락 줄 · 버튼 한 줄(작가 선택 A안, 2026-10-20).
// 눈에 띄는 색 버튼은 「해결」 하나만 둔다(보류까지 빨강이면 해결보다 먼저 눈이 간다).
// 플레이어 글은 믿지 않는 데이터다. 슬랙 서식 문자(& < >)를 바꿔 써서 <!channel> 같은 전체 호출·가짜 링크가 동작하지 않게 한다.
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.IssueCard;
import com.ljh.gameops.report.SourceChannel;
import com.slack.api.model.Attachment;
import com.slack.api.model.block.*;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.OptionObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.composition.TextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.ButtonElement;
import com.slack.api.model.block.element.PlainTextInputElement;
import com.slack.api.model.block.element.RadioButtonsElement;
import com.slack.api.model.block.element.StaticSelectElement;
import com.slack.api.model.view.View;
import com.slack.api.model.view.ViewClose;
import com.slack.api.model.view.ViewSubmit;
import com.slack.api.model.view.ViewTitle;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SlackCards {
    public static final String ACTION_STATUS_PREFIX = "issue_status_";
    public static final String ACTION_RECLASSIFY = "issue_reclassify";
    public static final String ACTION_DETACH = "issue_detach";
    public static final String VIEW_RECLASSIFY = "reclassify";
    public static final String VIEW_DETACH = "detach";
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M/d HH:mm");

    static final Map<Category, String> CATEGORY = com.ljh.gameops.issue.Labels.CATEGORY;
    static final Map<Severity, String> SEVERITY = com.ljh.gameops.issue.Labels.SEVERITY;
    static final Map<IssueStatus, String> STATUS = com.ljh.gameops.issue.Labels.STATUS;
    private static final Map<SourceChannel, String> SOURCE = Map.of(
            SourceChannel.DISCORD, "디스코드", SourceChannel.SLACK, "슬랙", SourceChannel.API, "API");

    private SlackCards() {}

    // ── 색과 표시 ──

    static String color(IssueCard c) {
        if (c.status() == IssueStatus.RESOLVED) return "#2EB67D";
        if (c.status() == IssueStatus.WONT_FIX) return "#616061";
        if (c.status() == IssueStatus.VERIFYING) return "#36C5F0";
        return switch (c.severity()) {
            case CRITICAL -> "#E01E5A";
            case HIGH -> "#E8912D";
            case MEDIUM -> "#ECB22E";
            case LOW -> "#A0A0A0";
        };
    }

    static String mark(IssueCard c) {
        if (c.status() == IssueStatus.RESOLVED) return "✅";
        if (c.status() == IssueStatus.WONT_FIX) return "⏸️";
        if (c.status() == IssueStatus.VERIFYING) return "🧪";
        return switch (c.severity()) {
            case CRITICAL -> "🔴";
            case HIGH -> "🟠";
            case MEDIUM -> "🟡";
            case LOW -> "⚪";
        };
    }

    /** 슬랙 서식 문자 바꿔 쓰기 — 슬랙 문서가 정한 세 글자(& < >). */
    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    public static String when(java.time.OffsetDateTime t) {
        return t == null ? "" : t.atZoneSameInstant(ZONE).format(WHEN);
    }

    // ── 카드 ──

    /** 카드 한 장(색 띠는 첨부의 color). 알림 미리보기용 한 줄은 fallback. */
    public static Attachment card(IssueCard c, String note) {
        return Attachment.builder()
                .color(color(c))
                .fallback(c.gameName() + " " + c.label() + " #" + c.issueId() + " · " + c.title())
                .blocks(blocks(c, note))
                .build();
    }

    static List<LayoutBlock> blocks(IssueCard c, String note) {
        List<LayoutBlock> b = new ArrayList<>();
        b.add(HeaderBlock.builder().text(plain(cut(mark(c) + " [" + c.gameName() + "] " + c.label() + " #" + c.issueId(), 150))).build());
        b.add(SectionBlock.builder().fields(List.<TextObject>of(
                md("*분류*\n" + CATEGORY.get(c.category())),
                md("*심각도*\n" + SEVERITY.get(c.severity())),
                md("*제보*\n" + c.reportCount() + "건"),
                md("*상태*\n" + STATUS.get(c.status())))).build());
        b.add(SectionBlock.builder().text(md(quote(c.quote()))).build());

        // 맥락은 한 블록에 모은다(블록이 6개를 넘으면 슬랙이 접는다). 방금 한 일이 있으면 맨 앞에
        List<ContextBlockElement> ctx = new ArrayList<>();
        if (c.lastAction() != null) ctx.add(md("*" + c.lastAction() + "*"));
        if (c.recurrenceOf() != null) ctx.add(md("🔁 재발 의심 · 최근 고친 #" + c.recurrenceOf() + "과 비슷"));
        // QA가 먼저 보는 재현 정보 한 줄(버전·기기·재현 순서·스크린샷). 플레이어가 쓴 값이라 바꿔 쓴다
        ctx.add(md("🧪 " + escape(cut(c.repro().line(), 200))));
        if (c.errors() != null) ctx.add(md("🐞 " + escape(cut(c.errors(), 200))));
        if (c.jiraKey() != null) ctx.add(md("🎫 Jira <" + c.jiraUrl() + "|" + escape(c.jiraKey()) + ">"));
        if (c.detachedFrom() != null) ctx.add(md("✂️ #" + c.detachedFrom() + "에서 떼어냄"));
        String src = c.source() == null ? "" : SOURCE.get(c.source()) + " · ";
        ctx.add(md(src + "처음 " + when(c.createdAt())));
        if (note != null && !note.isBlank()) ctx.add(md(note));
        b.add(ContextBlock.builder().elements(ctx).build());

        // 버튼은 한 줄에 모은다. 색 띠 첨부는 블록이 많으면 슬랙이 「더 보기」로 접어 두 번째 줄이 숨는다(미리보기에서 확인)
        List<BlockElement> buttons = new ArrayList<>(statusButtons(c));
        if (c.status().isOpen()) {
            buttons.add(button(ACTION_RECLASSIFY, "🏷️ 분류 고치기", String.valueOf(c.issueId()), null));
            // 제보가 하나뿐이면 떼어낼 게 없다(서버도 409 로 막는다)
            if (c.reportCount() >= 2) buttons.add(button(ACTION_DETACH, "✂️ 잘못 묶임 떼어내기", String.valueOf(c.issueId()), null));
        }
        b.add(ActionsBlock.builder().blockId("issue_" + c.issueId()).elements(buttons).build());
        return b;
    }

    private static List<BlockElement> statusButtons(IssueCard c) {
        long id = c.issueId();
        List<BlockElement> out = new ArrayList<>();
        switch (c.status()) {
            case OPEN -> {
                out.add(statusButton(id, IssueStatus.IN_PROGRESS, "진행 중", null));
                out.add(statusButton(id, IssueStatus.RESOLVED, "✅ 해결", "primary"));
                out.add(statusButton(id, IssueStatus.WONT_FIX, "보류", null));
            }
            case IN_PROGRESS -> {
                out.add(statusButton(id, IssueStatus.RESOLVED, "✅ 해결", "primary"));
                out.add(statusButton(id, IssueStatus.WONT_FIX, "보류", null));
            }
            // 고친 코드가 합쳐져 QA 확인 대기. 확인하면 해결(이때 플레이어 답장), 아직 나면 다시 열기
            case VERIFYING -> {
                out.add(statusButton(id, IssueStatus.RESOLVED, "✅ QA 확인 완료", "primary"));
                out.add(statusButton(id, IssueStatus.OPEN, "↩️ 아직 나요 · 다시 열기", null));
            }
            case RESOLVED, WONT_FIX -> out.add(statusButton(id, IssueStatus.OPEN, "다시 열기", null));
        }
        return out;
    }

    private static ButtonElement statusButton(long id, IssueStatus s, String label, String style) {
        return button(ACTION_STATUS_PREFIX + s.name().toLowerCase(), label, id + ":" + s.name(), style);
    }

    private static ButtonElement button(String actionId, String label, String value, String style) {
        return ButtonElement.builder().actionId(actionId).text(plain(label)).value(value).style(style).build();
    }

    /** 플레이어 원문을 인용 상자로. 줄마다 「>」를 붙이고 서식 문자는 바꿔 쓴다. */
    static String quote(String body) {
        String t = escape(cut(body, 500));
        if (t.isEmpty()) t = "(내용 없음)";
        StringBuilder s = new StringBuilder();
        for (String line : t.split("\n", -1)) s.append("> ").append(line).append('\n');
        return s.toString().stripTrailing();
    }

    // ── 규칙 반영 카드 ──

    public static final String ACTION_RULES_REVERT = "rules_revert";

    /** 「누가」 — 에이전트 키 이름·관리자 키 이름·슬랙 멤버를 사람이 읽는 말로 */
    static String actorLabel(String actor) {
        if (actor == null) return "?";
        if (actor.equals("ai-agent")) return "🤖 AI 에이전트";
        if (actor.startsWith("github:")) return "🔀 깃허브 " + escape(actor.substring(7)) + "(코드 반영)";
        if (actor.startsWith("jira:")) return "🎫 Jira " + escape(actor.substring(5));
        if (actor.startsWith("slack:")) {
            String id = actor.substring(6);
            return id.matches("[A-Z0-9_]{2,30}") ? "<@" + id + ">" : "슬랙 관리자";
        }
        return "관리자(" + escape(actor) + ")";
    }

    /** AI 가 분류 규칙을 고쳤을 때 — 파란 띠. 되돌리면 회색 띠에 「↩️ 되돌림 · 누가 · 언제」. 5블록 */
    public static Attachment rulesCard(com.ljh.gameops.notify.RuleChangeCard r) {
        String mark = r.active() ? "🤖" : "↩️";
        List<LayoutBlock> b = new ArrayList<>();
        b.add(HeaderBlock.builder().text(plain(mark + " 분류 규칙을 고쳤어요 · #" + r.versionId())).build());
        b.add(SectionBlock.builder().fields(List.<TextObject>of(
                md("*고친 쪽*\n" + actorLabel(r.actor())),
                md("*시험*\ndev " + pct(r.devBefore()) + " → " + pct(r.devAfter()) + " · 회귀 " + r.regressionTotal() + "/" + r.regressionTotal()))).build());
        StringBuilder t = new StringBuilder();
        for (String line : r.changes()) t.append("• ").append(escape(line)).append('\n');
        if (r.reason() != null && !r.reason().isBlank()) t.append(quote(r.reason()));
        b.add(SectionBlock.builder().text(md(cut(t.toString().strip(), 2900))).build());
        List<ContextBlockElement> ctx = new ArrayList<>();
        if (r.lastAction() != null) ctx.add(md("*" + r.lastAction() + "*"));
        ctx.add(md((r.active() ? "시험 통과해 자동 반영 · " : "지금은 안 씀 · ") + when(r.createdAt())));
        ctx.add(md("사람이 규칙 파일을 고치면 파일이 이겨요"));
        b.add(ContextBlock.builder().elements(ctx).build());
        if (r.active()) {
            b.add(ActionsBlock.builder().blockId("rules_" + r.versionId()).elements(List.of(
                    button(ACTION_RULES_REVERT, "↩️ 되돌리기", String.valueOf(r.versionId()), null))).build());
        }
        return Attachment.builder().color(r.active() ? "#4A90E2" : "#A0A0A0")
                .fallback("분류 규칙을 고쳤어요 #" + r.versionId()).blocks(b).build();
    }

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    // ── AI 피드백 정리 카드 ──

    public static final String ACTION_DIGEST_HANDOFF = "digest_handoff";
    public static final String ACTION_DIGEST_JIRA = "digest_jira";

    /** 보라 띠. 정리 글은 AI 가 쓴 글이지만 플레이어 글을 옮겼을 수 있어 서식 문자(& < >)는 바꿔 넣는다(굵게·목록은 그대로).
     *  아직 안 넘겼으면 「🤖 개발 AI에게 넘기기」 버튼, 넘겼으면 넘긴 이슈 링크와 누가·언제. 5블록 */
    public static Attachment digestCard(com.ljh.gameops.notify.DigestCard d) {
        String scope = (d.gameName() == null ? "전체 게임" : d.gameName()) + " · 최근 " + d.days() + "일 · 제보 " + d.reportCount() + "건";
        List<LayoutBlock> b = new ArrayList<>();
        b.add(HeaderBlock.builder().text(plain(cut("🧠 최근 피드백 정리 · " + (d.gameName() == null ? "전체" : d.gameName()), 150))).build());
        b.add(ContextBlock.builder().elements(List.of(md(escape(scope)))).build());
        b.add(SectionBlock.builder().text(md(cut(escape(d.text()), 2900))).build());
        // 정리한 쪽 · 넘긴 기록(깃허브 지시서 · Jira 티켓)은 한 맥락 블록에, 아직 안 한 일의 버튼은 한 줄에(5블록을 넘지 않게)
        List<ContextBlockElement> ctx = new ArrayList<>();
        ctx.add(md(actorLabel(d.actor()) + "가 정리 · " + when(d.createdAt())));
        if (!d.handoffs().isEmpty()) ctx.add(md(cut(links("🤖 *개발 AI에게 넘김* · ", d.handoffs()), 1400)));
        if (!d.jira().isEmpty()) ctx.add(md(cut(links("🎫 *Jira 티켓 " + d.jira().size() + "건* · ", d.jira()), 1400)));
        b.add(ContextBlock.builder().elements(ctx).build());
        List<BlockElement> todo = new ArrayList<>();
        if (d.handoffs().isEmpty()) todo.add(button(ACTION_DIGEST_HANDOFF, "🤖 개발 AI에게 넘기기", String.valueOf(d.digestId()), "primary"));
        if (d.jira().isEmpty() && d.jiraAvailable()) todo.add(button(ACTION_DIGEST_JIRA, "🎫 Jira 버그 티켓 만들기", String.valueOf(d.digestId()), null));
        if (!todo.isEmpty()) b.add(ActionsBlock.builder().elements(todo).build());
        return Attachment.builder().color("#7C3AED").fallback("최근 피드백 정리 · " + scope).blocks(b).build();
    }

    private static String links(String head, List<com.ljh.gameops.notify.DigestCard.Handoff> h) {
        StringBuilder s = new StringBuilder(head);
        for (int i = 0; i < h.size(); i++) {
            if (i > 0) s.append(", ");
            s.append('<').append(h.get(i).url()).append('|').append(escape(h.get(i).label())).append('>');
        }
        var last = h.get(h.size() - 1);
        return s.append(" · ").append(actorLabel(last.actor())).append(" · ").append(when(last.at())).toString();
    }

    // ── 입력 창 ──

    /** 분류 고치기 창 — 지금 값을 처음 선택으로 둔다. meta = 이슈 번호와 원래 카드 위치 */
    public static View reclassifyView(IssueCard c, String meta) {
        List<OptionObject> cats = new ArrayList<>();
        for (Category k : Category.values()) cats.add(option(CATEGORY.get(k), k.name()));
        List<OptionObject> sevs = new ArrayList<>();
        for (Severity k : new Severity[]{Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW}) sevs.add(option(SEVERITY.get(k), k.name()));
        return View.builder().type("modal").callbackId(VIEW_RECLASSIFY).privateMetadata(meta)
                .title(ViewTitle.builder().type("plain_text").text(cut("분류 고치기 #" + c.issueId(), 24)).build())
                .submit(ViewSubmit.builder().type("plain_text").text("저장").build())
                .close(ViewClose.builder().type("plain_text").text("취소").build())
                .blocks(List.of(
                        SectionBlock.builder().text(md(quote(c.quote()))).build(),
                        InputBlock.builder().blockId("category").label(plain("분류"))
                                .element(StaticSelectElement.builder().actionId("v").options(cats)
                                        .initialOption(option(CATEGORY.get(c.category()), c.category().name())).build()).build(),
                        InputBlock.builder().blockId("severity").label(plain("심각도"))
                                .element(StaticSelectElement.builder().actionId("v").options(sevs)
                                        .initialOption(option(SEVERITY.get(c.severity()), c.severity().name())).build()).build(),
                        reasonInput("예: 디자인 의견이라 불편으로")))
                .build();
    }

    public record ReportChoice(long reportId, String body, java.time.OffsetDateTime linkedAt) {}

    /** 떼어내기 창 — 이 이슈에 묶인 제보 중 하나를 고른다(최근 10건). */
    public static View detachView(IssueCard c, List<ReportChoice> choices, String meta) {
        List<OptionObject> opts = new ArrayList<>();
        for (ReportChoice r : choices) {
            opts.add(OptionObject.builder().text(plain(cut(r.body().replace('\n', ' '), 70)))
                    .description(plain(when(r.linkedAt()))).value(String.valueOf(r.reportId())).build());
        }
        return View.builder().type("modal").callbackId(VIEW_DETACH).privateMetadata(meta)
                .title(ViewTitle.builder().type("plain_text").text(cut("떼어내기 #" + c.issueId(), 24)).build())
                .submit(ViewSubmit.builder().type("plain_text").text("떼어내기").build())
                .close(ViewClose.builder().type("plain_text").text("취소").build())
                .blocks(List.of(
                        ContextBlock.builder().elements(List.of(md("고른 제보는 새 이슈가 되고, 그 글만 보고 다시 분류해요. 원래 이슈의 심각도는 그대로 둬요."))).build(),
                        InputBlock.builder().blockId("report").label(plain("다른 문제인 제보"))
                                .element(RadioButtonsElement.builder().actionId("v").options(opts).build()).build(),
                        reasonInput("예: 튕김과 느림은 다른 문제")))
                .build();
    }

    private static InputBlock reasonInput(String hint) {
        return InputBlock.builder().blockId("reason").optional(true).label(plain("이유 (고친 기록에 남아요)"))
                .element(PlainTextInputElement.builder().actionId("v").placeholder(plain(hint)).build()).build();
    }

    private static OptionObject option(String text, String value) {
        return OptionObject.builder().text(plain(text)).value(value).build();
    }

    static PlainTextObject plain(String t) { return PlainTextObject.builder().text(t).emoji(true).build(); }

    static MarkdownTextObject md(String t) { return MarkdownTextObject.builder().text(t).build(); }
}
