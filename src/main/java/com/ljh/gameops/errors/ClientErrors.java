// 게임 오류 기록(결정 37) — 게임에 넣은 코드 조각이 보내는 오류와, 플레이어가 붙여 넣은 「게임진단」 줄의 오류를 받는다.
// 같은 오류(메시지에서 숫자·따옴표 안 값을 지운 것 + 첫 호출 위치)는 한 줄에 횟수만 늘린다. 게임당 하루 새 오류 200종까지(넘치면 버림).
// 제보와는 「같은 게임·같은 시간대(앞뒤 30분)」로만 잇는다. 관련이 확정된 건 아니라서 카드·지시서에 「같은 시간대」라고 적는다
package com.ljh.gameops.errors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ClientErrors {
    static final int MSG_MAX = 500, STACK_MAX = 4000, MAX_NEW_PER_DAY = 200;
    public static final Duration WINDOW = Duration.ofMinutes(30);

    public record Incoming(String message, String stack, String version, String device, String context) {}

    public record Row(long id, String message, String stack, String version, String device, String context,
                      OffsetDateTime firstAt, OffsetDateTime lastAt, int count) {
        /** 「TypeError: x is undefined ×5 · v1.2.3」 */
        public String line() {
            return cut(message, 120) + " ×" + count + (version == null ? "" : " · v" + version);
        }

        /** 호출 위치 앞쪽 몇 줄 */
        public String topFrames(int n) {
            if (stack == null) return null;
            return String.join("\n", Arrays.stream(stack.split("\n")).map(String::strip).filter(l -> !l.isEmpty()).limit(n).toList());
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ClientErrors(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 기록한 줄 번호. 하루 새 오류 종류 한도를 넘으면 빈 값 */
    public Optional<Long> record(long gameId, Incoming in) {
        if (in == null || in.message() == null || in.message().isBlank()) return Optional.empty();
        String msg = cut(clean(in.message()), MSG_MAX);
        String stack = in.stack() == null ? null : cut(clean(in.stack()), STACK_MAX);
        String fp = fingerprint(msg, stack);
        OffsetDateTime now = OffsetDateTime.now(clock);
        int updated = jdbc.update("""
                UPDATE client_error SET count = count + 1, last_at = ?, build_version = coalesce(?, build_version), device = coalesce(?, device)
                WHERE game_id = ? AND fingerprint = ?
                """, now, cut(in.version(), 60), cut(in.device(), 200), gameId, fp);
        if (updated == 0) {
            Long today = jdbc.queryForObject("SELECT count(*) FROM client_error WHERE game_id = ? AND first_at >= ?", Long.class,
                    gameId, now.minusDays(1));
            if (today != null && today >= MAX_NEW_PER_DAY) return Optional.empty();
            jdbc.update("""
                    INSERT INTO client_error (game_id, fingerprint, message, stack, build_version, device, context, first_at, last_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (game_id, fingerprint) DO UPDATE SET count = client_error.count + 1, last_at = EXCLUDED.last_at
                    """, gameId, fp, msg, stack, cut(in.version(), 60), cut(in.device(), 200), cut(in.context(), 500), now, now);
        }
        return Optional.ofNullable(jdbc.queryForObject("SELECT id FROM client_error WHERE game_id = ? AND fingerprint = ?", Long.class, gameId, fp));
    }

    /** 제보 시각들 앞뒤 30분 안에 난 오류(많이 난 순, 최대 limit) */
    public List<Row> near(long gameId, Collection<OffsetDateTime> times, int limit) {
        if (times.isEmpty()) return List.of();
        Map<Long, Row> out = new LinkedHashMap<>();
        for (OffsetDateTime t : times) {
            jdbc.query("""
                    SELECT id, message, stack, build_version, device, context, first_at, last_at, count FROM client_error
                    WHERE game_id = ? AND last_at >= ? AND first_at <= ? ORDER BY count DESC LIMIT ?
                    """, rs -> {
                out.putIfAbsent(rs.getLong(1), new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getInt(9)));
            }, gameId, t.minus(WINDOW), t.plus(WINDOW), limit);
        }
        return out.values().stream().sorted(Comparator.comparingInt(Row::count).reversed()).limit(limit).toList();
    }

    /** 이 이슈에 묶인 제보들의 시각으로 찾는다 */
    public List<Row> forIssue(long issueId, int limit) {
        Long gameId = jdbc.query("SELECT game_id FROM issue WHERE id = ?", (rs, n) -> rs.getLong(1), issueId).stream().findFirst().orElse(null);
        if (gameId == null) return List.of();
        List<OffsetDateTime> times = jdbc.query("""
                SELECT r.received_at FROM report r JOIN issue_report l ON l.report_id = r.id WHERE l.issue_id = ? ORDER BY r.id DESC LIMIT 20
                """, (rs, n) -> rs.getObject(1, OffsetDateTime.class), issueId);
        return near(gameId, times, limit);
    }

    /** 카드 한 줄: 「🐞 같은 시간대 게임 오류 2종 · TypeError … ×5」. 없으면 null */
    public static String cardLine(List<Row> rows) {
        if (rows.isEmpty()) return null;
        return "같은 시간대 게임 오류 " + rows.size() + "종 · " + rows.get(0).line();
    }

    /** 숫자·따옴표 안 값·주소를 지워 같은 오류끼리 같은 지문이 되게 */
    static String fingerprint(String msg, String stack) {
        String m = msg.replaceAll("\\d+", "#").replaceAll("'[^']*'|\"[^\"]*\"", "_").replaceAll("https?://\\S+", "URL");
        String frame = stack == null ? "" : Arrays.stream(stack.split("\n")).map(String::strip)
                .filter(l -> !l.isEmpty() && !l.equals(msg.strip())).findFirst().orElse("").replaceAll(":\\d+:\\d+", "").replaceAll("\\?[^)\\s]*", "");
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((m + "|" + frame).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String clean(String s) {
        return s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }
}
