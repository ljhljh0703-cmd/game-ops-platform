// 놓친 제보 받기 — 봇이 꺼져 있던 동안 제보 채널에 올라온 글을, 봇이 접속할 때 채널 기록을 거슬러 읽어 접수한다.
// 디스코드는 꺼져 있던 동안의 글을 다시 보내 주지 않는다(접속이 잠깐 끊긴 경우만 이어 준다).
// 이 클래스는 기록(오래된 것부터)을 받아 「무엇을 접수할지」만 정한다. 외부 호출 없음(시험 대상).
//   · 이미 받은 글(같은 메시지 번호의 제보가 있음)은 건너뛴다. 이어 쓴 줄로 합쳐 받았던 다음 줄들도 건너뛴다
//   · 봇 글은 건너뛴다
//   · 같은 사람이 이어 쓴 줄은 실시간과 같은 기준(앞 줄 뒤 20초 안, 처음 줄부터 60초 안)으로 제보 하나로 합친다
//   · 답글은 재현 정보 더하기로 따로 넘긴다
package com.ljh.gameops.channel.discord;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class DiscordBackfill {
    static final Duration QUIET = Duration.ofSeconds(20), MAX = Duration.ofSeconds(60);

    /** 채널 기록의 글 한 개(디스코드 라이브러리 타입과 분리) */
    public record Msg(String id, String authorId, boolean fromBot, String text, String files, OffsetDateTime at,
                      String originalId, List<String> roles) {}

    /** 접수할 것: 새 제보(여러 줄을 합쳤으면 첫 글 번호) 또는 답글(재현 정보) */
    public record Item(DiscordInbound in, String originalId, OffsetDateTime at, String firstMessageId) {
        public boolean isReply() { return originalId != null; }
    }

    private DiscordBackfill() {}

    /**
     * @param msgs 오래된 것부터
     * @param seen 「msg:번호」 가 이미 접수된 글인지
     */
    public static List<Item> plan(List<Msg> msgs, String channelId, String channelName, Predicate<String> seen) {
        List<Item> out = new ArrayList<>();
        Map<String, OffsetDateTime[]> run = new HashMap<>(); // 사람별 [줄 묶음 시작, 마지막 줄] — 이미 받은 묶음 포함
        Map<String, Boolean> runSeen = new HashMap<>();
        Group g = null;
        for (Msg m : msgs) {
            if (m.fromBot()) { g = flush(g, out, channelId, channelName); continue; }
            if (m.originalId() != null) {
                g = flush(g, out, channelId, channelName);
                out.add(new Item(inbound(m, channelId, channelName, m.text(), m.files()), m.originalId(), m.at(), m.id()));
                continue;
            }
            OffsetDateTime[] r = run.get(m.authorId());
            boolean continues = r != null && !m.at().isAfter(r[1].plus(QUIET)) && !m.at().isAfter(r[0].plus(MAX));
            if (seen.test("msg:" + m.id())) {
                g = flush(g, out, channelId, channelName);
                run.put(m.authorId(), new OffsetDateTime[]{m.at(), m.at()});
                runSeen.put(m.authorId(), true);
                continue;
            }
            if (continues && Boolean.TRUE.equals(runSeen.get(m.authorId()))) { // 이미 받은 제보에 이어 쓴 줄
                r[1] = m.at();
                continue;
            }
            if (continues && g != null && g.author.equals(m.authorId())) {
                g.parts.add(m);
                r[1] = m.at();
                continue;
            }
            g = flush(g, out, channelId, channelName);
            g = new Group(m.authorId());
            g.parts.add(m);
            run.put(m.authorId(), new OffsetDateTime[]{m.at(), m.at()});
            runSeen.put(m.authorId(), false);
        }
        flush(g, out, channelId, channelName);
        return out;
    }

    private static final class Group {
        final String author;
        final List<Msg> parts = new ArrayList<>();
        Group(String author) { this.author = author; }
    }

    private static Group flush(Group g, List<Item> out, String channelId, String channelName) {
        if (g == null || g.parts.isEmpty()) return null;
        Msg first = g.parts.get(0);
        List<String> texts = new ArrayList<>(), files = new ArrayList<>();
        for (Msg p : g.parts) {
            if (p.text() != null && !p.text().isBlank()) texts.add(p.text().strip());
            if (p.files() != null && !p.files().isBlank()) files.add(p.files());
        }
        if (texts.isEmpty()) return null; // 글 없이 그림만 올린 건 제보로 받지 않는다(실시간과 같음)
        out.add(new Item(inbound(first, channelId, channelName, String.join("\n", texts), files.isEmpty() ? null : String.join("\n", files)),
                null, first.at(), first.id()));
        return null;
    }

    private static DiscordInbound inbound(Msg m, String channelId, String channelName, String text, String files) {
        return new DiscordInbound("msg:" + m.id(), channelId, channelName, m.authorId(), false, null, text, files,
                channelId + ":" + m.id(), null, m.roles());
    }
}
