// 디스코드 답장 카드(embed) 모양 — 접수 답장과 해결 답장. 외부 호출 없이 모양만 만든다(시험 대상).
// 색은 슬랙 카드와 같은 심각도 색, 해결은 초록. 플레이어 글은 카드 제목에 넣지 않는다(이슈 제목만, 길이 제한).
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.util.Map;

public final class DiscordCards {
    static final int GREEN = 0x2EB67D, GRAY = 0xA0A0A0, RED = 0xE01E5A;
    private static final Map<Category, String> CATEGORY = Map.of(
            Category.CRASH, "크래시", Category.BUG, "버그", Category.PERFORMANCE, "성능", Category.BALANCE, "밸런스",
            Category.PAYMENT, "결제", Category.UX, "불편", Category.OTHER, "기타");
    private static final Map<Severity, String> SEVERITY = Map.of(
            Severity.CRITICAL, "긴급", Severity.HIGH, "높음", Severity.MEDIUM, "보통", Severity.LOW, "낮음");
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
        EmbedBuilder e = new EmbedBuilder().setFooter(FOOTER);
        switch (r.kind()) {
            case ERROR -> {
                return e.setColor(RED).setTitle("⚠️ 접수하지 못했어요").setDescription(r.text()).build();
            }
            case NEW -> e.setTitle("📨 접수했어요 · 이슈 #" + r.issueId());
            case ATTACHED -> e.setTitle("🔗 같은 제보에 합쳤어요 · 이슈 #" + r.issueId());
            case DUPLICATE -> e.setTitle("👌 이미 접수된 제보예요 · 이슈 #" + r.issueId());
        }
        e.setColor(color(r.severity())).setDescription(r.text());
        if (r.category() != null) e.addField("분류", CATEGORY.get(r.category()), true);
        if (r.severity() != null) e.addField("심각도", SEVERITY.get(r.severity()), true);
        e.addField("같은 제보", r.reportCount() + "건", true);
        if (canReplyLater && r.kind() != DiscordReportHandler.Reply.Kind.DUPLICATE) {
            e.setFooter(FOOTER + " · 고치면 이 글에 답장으로 알려 드려요");
        }
        return e.build();
    }

    /** 해결 답장 — 제보한 글에 단다. */
    public static MessageEmbed resolved(String gameName, long issueId, String title) {
        return new EmbedBuilder()
                .setColor(GREEN)
                .setTitle("✅ 고쳤어요 · 이슈 #" + issueId)
                .setDescription("[" + gameName + "] 「" + cut(title, 80) + "」\n개발팀이 해결로 처리했어요. 제보해 주셔서 고마워요!\n다시 생기면 이 채널에 또 알려 주세요.")
                .setFooter(FOOTER)
                .build();
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.strip().replace("\n", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
