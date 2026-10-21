// 패치노트(결정 39) — 마지막 공지 뒤 해결된 버그와 게임 저장소에 합쳐진 변경을 모아 초안을 쓰고(슬랙 카드),
// 사람이 고치거나 그대로 [디스코드에 공지]를 누르면 게임 공지 채널(없으면 버그 제보 채널)에 올린다.
// 공지는 바깥에 나가는 글이라 사람만 누른다(AI 키로는 초안까지). 같은 초안은 한 번만 공지된다
package com.ljh.gameops.patch;

import com.ljh.gameops.audit.AuditLog;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.common.ConflictException;
import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.handoff.GitHubRepoReader;
import com.ljh.gameops.issue.Labels;
import com.ljh.gameops.notify.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PatchNoteService implements PatchNoteDelivery {
    private static final Logger log = LoggerFactory.getLogger(PatchNoteService.class);
    static final int TEXT_MAX = 3500;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d");

    private final JdbcTemplate jdbc;
    private final GameCatalog games;
    private final GameRepository gameRepo;
    private final PatchNoteWriter writer;
    private final GitHubRepoReader github;
    private final OutboxService outbox;
    private final AuditLogRepository audit;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<Announcer> announcer;
    private final Clock clock;

    public PatchNoteService(JdbcTemplate jdbc, GameCatalog games, GameRepository gameRepo, PatchNoteWriter writer, GitHubRepoReader github,
                            OutboxService outbox, AuditLogRepository audit, ApplicationEventPublisher events,
                            ObjectProvider<Announcer> announcer, Clock clock) {
        this.jdbc = jdbc;
        this.games = games;
        this.gameRepo = gameRepo;
        this.writer = writer;
        this.github = github;
        this.outbox = outbox;
        this.audit = audit;
        this.events = events;
        this.announcer = announcer;
        this.clock = clock;
    }

    /** id 가 null 이면 알릴 게 없어 초안을 안 만들었다 */
    public record Draft(Long id, int fixed, int changes, boolean byAi) {}

    /**
     * 초안 만들기. 시작 = 마지막 공지 끝 시각과 days 일 전 중 늦은 쪽(이미 알린 걸 다시 쓰지 않게). 끝 = 지금
     */
    @Transactional
    public Draft draft(String gameCode, int days, String actor) {
        GameSpec g = games.byCode(gameCode).orElseThrow(() -> new NotFoundException("등록되지 않은 게임: " + gameCode));
        long gameId = gameRepo.findByCode(gameCode).orElseThrow().getId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime from = now.minusDays(days);
        OffsetDateTime last = jdbc.query("SELECT max(to_at) FROM patch_note WHERE game_id = ? AND status IN ('PUBLISHING','PUBLISHED')",
                (rs, n) -> rs.getObject(1, OffsetDateTime.class), gameId).stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (last != null && last.isAfter(from)) from = last;

        List<PatchNoteWriter.Fixed> fixed = jdbc.query("""
                SELECT id, title, category, report_count FROM issue
                WHERE game_id = ? AND status = 'RESOLVED' AND resolved_at > ? AND resolved_at <= ?
                ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, report_count DESC, id
                """, (rs, n) -> new PatchNoteWriter.Fixed(rs.getLong(1), rs.getString(2),
                Labels.CATEGORY.get(com.ljh.gameops.issue.Category.valueOf(rs.getString(3))), rs.getInt(4)), gameId, from, now);
        List<PatchNoteWriter.Change> changes = new ArrayList<>();
        if (g.repo() != null) {
            try {
                for (var pr : github.recentlyMerged(g.repo())) {
                    if (pr.mergedAt() == null || !pr.mergedAt().isAfter(from) || pr.mergedAt().isAfter(now)) continue;
                    changes.add(new PatchNoteWriter.Change(changes.size() + 1, pr.title()));
                }
            } catch (RuntimeException e) {
                log.warn("패치노트: 합쳐진 변경을 못 읽었어요({}): {}", g.repo(), e.getMessage()); // 고친 버그만으로 쓴다
            }
        }
        if (fixed.isEmpty() && changes.isEmpty()) return new Draft(null, 0, 0, false);

        PatchNoteWriter.Result r = writer.write(g.name(), fixed, changes, reportChannelLabel(g));
        String title = "📝 " + g.name() + " 업데이트 노트 · " + now.atZoneSameInstant(ZoneId.of("Asia/Seoul")).format(DAY);
        String ids = String.join(",", fixed.stream().map(f -> String.valueOf(f.id())).toList());
        Long id = jdbc.queryForObject("""
                INSERT INTO patch_note (game_id, from_at, to_at, title, text, issue_ids, status, actor, by_ai)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?) RETURNING id
                """, Long.class, gameId, from, now, title, cut(r.text()), ids, actor, r.byAi());
        audit.save(new AuditLog(actor, "PATCH_DRAFT", "patch:" + id, g.code() + " 고친 버그 " + fixed.size() + " · 바뀐 점 " + changes.size()));
        outbox.enqueue(NotificationType.PATCH_NOTE, Map.of("patchId", id, "gameCode", g.code()));
        return new Draft(id, fixed.size(), changes.size(), r.byAi());
    }

    @Transactional
    public void edit(long id, String text, String actor, String channel, String ts) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("내용이 비었어요");
        int n = jdbc.update("UPDATE patch_note SET text = ?, edited_by = ? WHERE id = ? AND status = 'DRAFT'", cut(text.strip()), actor, id);
        if (n == 0) throw new ConflictException("이미 공지했거나 없는 패치노트예요");
        audit.save(new AuditLog(actor, "PATCH_EDIT", "patch:" + id, "고침"));
        events.publishEvent(new PatchChanged(id, channel, ts));
    }

    /** 공지 예약 — 실제로 올리는 건 대기열(디스코드가 잠깐 끊겨도 다시 보낸다) */
    @Transactional
    public void publish(long id, String actor, String channel, String ts) {
        int n = jdbc.update("UPDATE patch_note SET status = 'PUBLISHING', published_by = ? WHERE id = ? AND status = 'DRAFT'", actor, id);
        if (n == 0) throw new ConflictException("이미 공지했거나 없는 패치노트예요");
        audit.save(new AuditLog(actor, "PATCH_PUBLISH", "patch:" + id, "공지"));
        outbox.enqueue(NotificationType.PATCH_PUBLISH, Map.of("patchId", id));
        events.publishEvent(new PatchChanged(id, channel, ts));
    }

    @Override
    @Transactional
    public void deliver(long id) {
        var row = jdbc.queryForMap("SELECT p.title, p.text, p.status, g.code FROM patch_note p JOIN game g ON g.id = p.game_id WHERE p.id = ?", id);
        if (!"PUBLISHING".equals(row.get("status"))) return; // 이미 올렸거나 취소
        GameSpec g = games.byCode((String) row.get("code")).orElseThrow();
        String channelId = announceChannel(g);
        if (channelId == null) throw new IllegalStateException(g.name() + " 공지할 디스코드 채널 ID 가 없어요(games.json 의 announceChannel)");
        Announcer a = announcer.getIfAvailable();
        if (a == null) throw new IllegalStateException("디스코드 봇이 꺼져 있어요");
        String msg = a.announce(channelId, (String) row.get("title"), (String) row.get("text"));
        jdbc.update("UPDATE patch_note SET status = 'PUBLISHED', published_at = ?, discord_channel = ?, discord_message = ? WHERE id = ?",
                OffsetDateTime.now(clock), channelId, msg, id);
        log.info("패치노트 #{} 공지 → 디스코드 {}", id, channelId);
        events.publishEvent(new PatchChanged(id, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public PatchCard card(long id) {
        return jdbc.query("""
                SELECT p.id, g.code, p.title, p.text, p.status, p.by_ai, p.actor, p.created_at, p.edited_by, p.published_by, p.published_at,
                       p.issue_ids
                FROM patch_note p JOIN game g ON g.id = p.game_id WHERE p.id = ?
                """, (rs, n) -> {
            GameSpec g = games.byCode(rs.getString(2)).orElse(null);
            String ids = rs.getString(12);
            return new PatchCard(rs.getLong(1), g == null ? rs.getString(2) : g.name(), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getBoolean(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class), rs.getString(9), rs.getString(10),
                    rs.getObject(11, OffsetDateTime.class), g == null ? null : announceLabel(g),
                    ids == null || ids.isBlank() ? 0 : ids.split(",").length);
        }, id).stream().findFirst().orElseThrow(() -> new NotFoundException("패치노트 없음: " + id));
    }

    public List<PatchCard> recent(String gameCode, int limit) {
        return jdbc.queryForList("""
                SELECT p.id FROM patch_note p JOIN game g ON g.id = p.game_id WHERE (CAST(? AS VARCHAR) IS NULL OR g.code = ?)
                ORDER BY p.id DESC LIMIT ?
                """, Long.class, gameCode, gameCode, limit).stream().map(this::card).toList();
    }

    /** 공지 채널: announceChannel → 버그 제보 채널 중 ID 인 것 */
    static String announceChannel(GameSpec g) {
        if (g.announceChannel() != null) return g.announceChannel();
        return g.discordChannels().stream().filter(c -> c.matches("\\d{5,25}")).findFirst().orElse(null);
    }

    static String announceLabel(GameSpec g) {
        return g.announceChannel() != null ? "공지 채널" : reportChannelLabel(g);
    }

    /** 플레이어에게 보일 제보 채널 이름(「#보석타워-버그-제보」) */
    static String reportChannelLabel(GameSpec g) {
        return g.discordChannels().stream().filter(c -> !c.matches("\\d+")).findFirst().map(c -> "#" + c).orElse(null);
    }

    private static String cut(String s) { return s.length() > TEXT_MAX ? s.substring(0, TEXT_MAX - 1) + "…" : s; }
}
