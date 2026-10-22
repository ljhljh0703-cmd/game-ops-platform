// 끊어 쓴 글 합치기(결정 44) — 채팅은 한 제보를 「보스에서」 「튕겨요」 「아이폰임」처럼 여러 줄로 나눠 보내곤 한다.
// 같은 사람이 같은 채널에 이어 쓴 글은 조용해질 때까지(마지막 글 뒤 20초, 처음 글부터 최대 60초) 모았다가 제보 하나로 넘긴다.
// 그래서 끊어 쓴 줄은 도배 한도에 따로 세지 않고, 분류·묶기도 전체 글로 한다. 답장은 처음 글에 한 번.
// 서버를 끌 때는 모아 둔 것을 바로 넘긴다(잃지 않게).
package com.ljh.gameops.channel.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class DiscordMessageBuffer {
    static final int MAX_PARTS = 10, MAX_CHARS = 4000;

    private final long quietMs, maxMs;
    private final Consumer<DiscordInbound> sink;
    private final ScheduledExecutorService timer;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final class Pending {
        final DiscordInbound first;
        final long startedAt = System.currentTimeMillis();
        final List<String> texts = new ArrayList<>();
        final List<String> files = new ArrayList<>();
        ScheduledFuture<?> due;

        Pending(DiscordInbound first) { this.first = first; }

        int chars() { return texts.stream().mapToInt(String::length).sum() + texts.size(); }
    }

    public DiscordMessageBuffer(long quietMs, long maxMs, Consumer<DiscordInbound> sink) {
        this.quietMs = quietMs;
        this.maxMs = maxMs;
        this.sink = sink;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discord-buffer");
            t.setDaemon(true);
            return t;
        });
    }

    static String key(DiscordInbound in) { return in.channelId() + "|" + in.authorId(); }

    public void add(DiscordInbound in) {
        if (quietMs <= 0) { sink.accept(in); return; }
        String k = key(in);
        synchronized (pending) {
            Pending p = pending.get(k);
            if (p != null && (p.texts.size() >= MAX_PARTS || p.chars() + len(in.content()) > MAX_CHARS)) {
                flush(k);
                p = null;
            }
            if (p == null) {
                p = new Pending(in);
                pending.put(k, p);
            }
            if (in.content() != null && !in.content().isBlank()) p.texts.add(in.content().strip());
            if (in.attachmentUrls() != null && !in.attachmentUrls().isBlank()) p.files.add(in.attachmentUrls());
            if (p.due != null) p.due.cancel(false);
            long wait = Math.max(0, Math.min(quietMs, p.startedAt + maxMs - System.currentTimeMillis()));
            p.due = timer.schedule(() -> flush(k), wait, TimeUnit.MILLISECONDS);
        }
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }

    /** 모은 글을 제보 하나로 넘긴다 */
    void flush(String k) {
        Pending p;
        synchronized (pending) {
            p = pending.remove(k);
            if (p == null) return;
            if (p.due != null) p.due.cancel(false);
        }
        String text = String.join("\n", p.texts);
        String files = p.files.isEmpty() ? null : String.join("\n", p.files);
        try {
            sink.accept(p.first.withContent(text, files));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(DiscordMessageBuffer.class).warn("모은 제보 넘기기 실패: {}", e.getMessage());
        }
    }

    public int pendingCount() { return pending.size(); }

    public void flushAll() {
        for (String k : List.copyOf(pending.keySet())) flush(k);
        timer.shutdown();
    }
}
