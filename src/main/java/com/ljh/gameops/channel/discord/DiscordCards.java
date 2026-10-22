// 디스코드 답장 카드(embed) 모양 — 접수 답장과 해결 답장. 외부 호출 없이 모양만 만든다(시험 대상).
// 색은 슬랙 카드와 같은 심각도 색, 해결은 초록. 플레이어 글은 카드 제목에 넣지 않는다(이슈 제목만, 길이 제한).
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.issue.Severity;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import com.ljh.gameops.report.Lang;


public final class DiscordCards {
    static final int GREEN = 0x2EB67D, GRAY = 0xA0A0A0, RED = 0xE01E5A;
    static final String FOOTER = "게임 운영 봇";

    private DiscordCards() {}

    static int color(Severity s) {
        if (s == null) return GRAY;
        return switch (s) {
            case CRITICAL -> 0xE01E5A;
            case HIGH -> 0xE8912D;
            case MEDIUM -> 0xECB22E;
            case LOW -> GRAY;
        };
    }

    /** 접수 답장. canReplyLater 면 「고치면 이 글에 답장」 약속을 붙인다(채널 글만 가능, /report 명령은 나중에 답장할 수 없음). */
    public static MessageEmbed intake(DiscordReportHandler.Reply r, boolean canReplyLater) {
        String l = r.lang();
        EmbedBuilder e = new EmbedBuilder().setFooter(PlayerText.footer(l));
        switch (r.kind()) {
            case ERROR -> {
                return e.setColor(RED).setTitle(PlayerText.titleError(l)).setDescription(r.text()).build();
            }
            case NEW -> e.setTitle(PlayerText.titleNew(l, r.issueId()));
            case ATTACHED -> e.setTitle(PlayerText.titleAttached(l, r.issueId()));
            case DUPLICATE -> e.setTitle(PlayerText.titleDuplicate(l, r.issueId()));
        }
        e.setColor(color(r.severity())).setDescription(r.text());
        if (r.category() != null) e.addField(PlayerText.fieldCategory(l), PlayerText.category(l, r.category()), true);
        if (r.severity() != null) e.addField(PlayerText.fieldSeverity(l), PlayerText.severity(l, r.severity()), true);
        e.addField(PlayerText.fieldCount(l), PlayerText.reports(l, r.reportCount()), true);
        if (!r.missing().isEmpty()) {
            e.addField(PlayerText.fieldMissing(l), String.join(" · ", PlayerText.missing(l, r.missing()))
                    + "\n" + (canReplyLater ? PlayerText.replyHere(l) : PlayerText.useSlashFields(l)), false);
        }
        if (canReplyLater && r.kind() != DiscordReportHandler.Reply.Kind.DUPLICATE) {
            e.setFooter(PlayerText.footer(l) + PlayerText.footerPromise(l));
        }
        return e.build();
    }

    /** 해결 답장 — 제보한 글에 단다. */
    public static MessageEmbed resolved(String gameName, long issueId, String title) {
        return resolved(gameName, issueId, title, Lang.KO);
    }

    /** 플레이어 말로. 외국어 제보자에게는 (한국어) 이슈 제목 대신 그 사람이 쓴 글 앞부분을 보인다 */
    public static MessageEmbed resolved(String gameName, long issueId, String title, String lang) {
        String l = PlayerText.of(lang);
        return new EmbedBuilder()
                .setColor(GREEN)
                .setTitle(PlayerText.resolvedTitle(l, issueId))
                .setDescription("[" + gameName + "] 「" + cut(title, 80) + "」\n" + PlayerText.resolvedBody(l))
                .setFooter(PlayerText.footer(l))
                .build();
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.strip().replace("\n", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
