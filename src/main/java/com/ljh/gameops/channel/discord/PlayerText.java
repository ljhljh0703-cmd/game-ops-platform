// 플레이어에게 보이는 디스코드 문구 — 제보한 글의 언어로(결정 42). 한국어 · 영어 · 일본어, 나머지 언어는 영어.
// 운영자(슬랙)는 언제나 한국어, 플레이어(디스코드)는 자기 말. 문구는 이 한 곳에서만 고친다.
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.report.Lang;

import java.util.List;
import java.util.Map;

public final class PlayerText {
    private PlayerText() {}

    /** 한국어·영어·일본어 중 하나. 중국어·기타는 영어로 답한다 */
    public static String of(String lang) {
        if (Lang.JA.equals(lang)) return Lang.JA;
        if (lang == null || Lang.KO.equals(lang)) return Lang.KO;
        return Lang.EN;
    }

    private static String pick(String lang, String ko, String en, String ja) {
        return switch (of(lang)) { case Lang.EN -> en; case Lang.JA -> ja; default -> ko; };
    }

    static String received(String lang, long id) {
        return pick(lang, "접수했어요. 이슈 #%d 로 개발팀에 전달합니다.", "Got it! Sent to the dev team as issue #%d.",
                "受け付けました。課題 #%d として開発チームに伝えます。").formatted(id);
    }

    static String receivedRecurrence(String lang, long id, long prev) {
        return pick(lang, "접수했어요. 이슈 #%d 로 전달합니다. 최근 고친 문제(#%d)가 다시 생긴 것 같아 함께 알렸어요.",
                "Got it! Sent as issue #%d. It looks like a recently fixed problem (#%d) came back, so we flagged that too.",
                "受け付けました。課題 #%d として伝えます。最近直した問題(#%d)の再発のようなので一緒に伝えました。").formatted(id, prev);
    }

    static String attached(String lang, long id, int count) {
        return pick(lang, "비슷한 제보가 있어 이슈 #%d 에 합쳤어요. 같은 제보 %d건째입니다.",
                "A similar report already exists, so we added yours to issue #%d. That makes %d reports.",
                "似た報告があったので課題 #%d にまとめました。同じ報告は%d件目です。").formatted(id, count);
    }

    static String duplicate(String lang, long id) {
        return pick(lang, "이미 접수된 제보예요. 이슈 #%d", "This report was already received. Issue #%d",
                "この報告はすでに受け付け済みです。課題 #%d").formatted(id);
    }

    static String reproAdded(String lang, long id) {
        return pick(lang, "재현 정보를 이슈 #%d 에 더했어요. 고마워요!", "Added your details to issue #%d. Thanks!",
                "課題 #%d に詳しい情報を追加しました。ありがとうございます!").formatted(id);
    }

    static String unknownGame(String lang, String game) {
        return pick(lang, "등록되지 않은 게임이에요: ", "This game isn't registered: ", "登録されていないゲームです: ") + game;
    }

    static String down(String lang) {
        return pick(lang, "지금 접수가 안 되고 있어요. 잠시 뒤 다시 올려 주세요.", "We can't take reports right now. Please try again in a moment.",
                "現在受け付けできません。しばらくしてからもう一度送ってください。");
    }

    static String rateLimited(String lang) {
        return pick(lang, "짧은 시간에 제보가 많아요. 10분쯤 뒤에 다시 올려 주세요. 같은 문제면 처음 글에 답글로 더해 주시면 돼요.",
                "Too many reports in a short time. Please wait about 10 minutes. If it's the same problem, just reply to your first post.",
                "短時間に報告が多すぎます。10分ほど待ってから送ってください。同じ問題なら最初の投稿に返信で追加してください。");
    }

    static String flood(String lang) {
        return pick(lang, "지금 같은 게임에 제보가 몰려 확인 중이에요. 잠시 뒤 다시 올려 주세요.",
                "We're getting a lot of reports for this game right now. Please try again a little later.",
                "現在このゲームへの報告が集中しています。しばらくしてからもう一度送ってください。");
    }

    static String emptyBody(String lang) {
        return pick(lang, "내용을 적어 주세요.", "Please describe the problem.", "内容を書いてください。");
    }

    // 카드
    static String titleError(String l) { return pick(l, "⚠️ 접수하지 못했어요", "⚠️ Couldn't take your report", "⚠️ 受け付けできませんでした"); }
    static String titleNew(String l, long id) { return pick(l, "📨 접수했어요 · 이슈 #", "📨 Received · Issue #", "📨 受付 · 課題 #") + id; }
    static String titleAttached(String l, long id) { return pick(l, "🔗 같은 제보에 합쳤어요 · 이슈 #", "🔗 Added to an existing report · Issue #", "🔗 同じ報告にまとめました · 課題 #") + id; }
    static String titleDuplicate(String l, long id) { return pick(l, "👌 이미 접수된 제보예요 · 이슈 #", "👌 Already received · Issue #", "👌 受付済み · 課題 #") + id; }
    static String fieldCategory(String l) { return pick(l, "분류", "Type", "種類"); }
    static String fieldSeverity(String l) { return pick(l, "심각도", "Severity", "深刻度"); }
    static String fieldCount(String l) { return pick(l, "같은 제보", "Same reports", "同じ報告"); }
    static String reports(String l, int n) { return pick(l, n + "건", String.valueOf(n), n + "件"); }
    static String fieldMissing(String l) { return pick(l, "더 알려 주시면 빨리 고쳐요", "Tell us more and we can fix it faster", "詳しく教えていただけると早く直せます"); }
    static String replyHere(String l) { return pick(l, "이 글에 답글로 적어 주세요.", "Just reply to this message.", "このメッセージに返信で書いてください。"); }
    static String useSlashFields(String l) { return pick(l, "/report 의 입력칸에 적을 수 있어요.", "You can fill them in with /report.", "/report の入力欄に書けます。"); }
    static String footerPromise(String l) { return pick(l, " · 고치면 이 글에 답장으로 알려 드려요", " · We'll reply here when it's fixed", " · 直ったらここに返信します"); }
    static String footer(String l) { return pick(l, "게임 운영 봇", "Game ops bot", "ゲーム運営ボット"); }

    static String resolvedTitle(String l, long id) { return pick(l, "✅ 고쳤어요 · 이슈 #", "✅ Fixed · Issue #", "✅ 直りました · 課題 #") + id; }
    static String resolvedBody(String l) {
        return pick(l, "개발팀이 해결로 처리했어요. 제보해 주셔서 고마워요!\n다시 생기면 이 채널에 또 알려 주세요.",
                "The dev team marked this as fixed. Thanks for reporting!\nIf it happens again, let us know in this channel.",
                "開発チームが修正済みにしました。報告ありがとうございました!\nまた起きたらこのチャンネルで教えてください。");
    }

    private static final Map<Category, String[]> CATEGORY = Map.of(
            Category.CRASH, new String[]{"크래시", "Crash", "クラッシュ"}, Category.BUG, new String[]{"버그", "Bug", "バグ"},
            Category.PERFORMANCE, new String[]{"성능", "Performance", "動作の重さ"}, Category.BALANCE, new String[]{"밸런스", "Balance", "バランス"},
            Category.PAYMENT, new String[]{"결제", "Payment", "課金"}, Category.UX, new String[]{"불편", "Usability", "使いにくさ"},
            Category.OTHER, new String[]{"기타", "Other", "その他"});
    private static final Map<Severity, String[]> SEVERITY = Map.of(
            Severity.CRITICAL, new String[]{"긴급", "Urgent", "緊急"}, Severity.HIGH, new String[]{"높음", "High", "高"},
            Severity.MEDIUM, new String[]{"보통", "Medium", "中"}, Severity.LOW, new String[]{"낮음", "Low", "低"});

    static String category(String l, Category c) { String[] v = CATEGORY.get(c); return pick(l, v[0], v[1], v[2]); }
    static String severity(String l, Severity s) { String[] v = SEVERITY.get(s); return pick(l, v[0], v[1], v[2]); }

    /** ReproInfo.missing() 의 한국어 이름을 플레이어 말로 */
    static List<String> missing(String l, List<String> ko) {
        return ko.stream().map(m -> switch (m) {
            case "게임 버전" -> pick(l, m, "game version", "ゲームのバージョン");
            case "기기" -> pick(l, m, "device", "端末");
            case "재현 순서" -> pick(l, m, "steps to reproduce", "再現手順");
            case "스크린샷" -> pick(l, m, "screenshot", "スクリーンショット");
            default -> m;
        }).toList();
    }
}
