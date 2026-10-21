// 최근 피드백 모아 보기와 AI 정리 올리기(결정 27) — AI 가 최근 제보를 읽고 「자주 나온 문제·새 문제·먼저 볼 것」을 정리해 올리면
// 기록으로 남기고 슬랙에 카드로 보낸다. 플레이어 글은 믿지 않는 데이터라 AI 쪽(MCP)이 감싸서 넘긴다.
package com.ljh.gameops.feedback;

import com.ljh.gameops.audit.AuditLog;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FeedbackService {
    public static final int MAX_TEXT = 2800;

    private final JdbcTemplate jdbc;
    private final GameRepository games;
    private final OutboxService outbox;
    private final AuditLogRepository audit;

    public FeedbackService(JdbcTemplate jdbc, GameRepository games, OutboxService outbox, AuditLogRepository audit) {
        this.jdbc = jdbc;
        this.games = games;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** version·device·steps·attachments = 제보의 재현 정보(V12). 없으면 null */
    public record Item(long reportId, long issueId, String game, OffsetDateTime receivedAt, String body,
                       String category, String severity, String status, int issueReportCount, String issueTitle,
                       String version, String device, String steps, String attachments) {
        public Item(long reportId, long issueId, String game, OffsetDateTime receivedAt, String body,
                    String category, String severity, String status, int issueReportCount, String issueTitle) {
            this(reportId, issueId, game, receivedAt, body, category, severity, status, issueReportCount, issueTitle, null, null, null, null);
        }
    }

    /** 최근 days 일 제보(새것부터). game 이 null 이면 모든 게임 */
    @Transactional(readOnly = true)
    public List<Item> recent(String gameCode, int days, int limit) {
        Long gameId = gameCode == null || gameCode.isBlank() ? null : game(gameCode).getId();
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);
        return jdbc.query("""
                SELECT r.id, i.id, g.code, r.received_at, r.body, i.category, i.severity, i.status, i.report_count, i.title,
                       r.build_version, r.device, r.repro_steps, r.attachment_urls
                FROM report r
                JOIN issue_report l ON l.report_id = r.id
                JOIN issue i ON i.id = l.issue_id
                JOIN game g ON g.id = r.game_id
                WHERE r.received_at >= ? AND (CAST(? AS BIGINT) IS NULL OR r.game_id = ?)
                ORDER BY r.received_at DESC, r.id DESC
                LIMIT ?
                """, (rs, n) -> new Item(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14)), since, gameId, gameId, limit);
    }

    /** AI 정리를 기록하고 슬랙 카드로 보낸다. 게임을 고르면 그 게임 채널, 아니면 공용 채널 */
    @Transactional
    public long post(String gameCode, int days, String text, String actor) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("정리 글이 비어 있어요");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("정리 글은 " + MAX_TEXT + "자까지 받아요(슬랙 카드 한 칸)");
        Long gameId = gameCode == null || gameCode.isBlank() ? null : game(gameCode).getId();
        int count = recent(gameCode, days, 10_000).size();
        Long id = jdbc.queryForObject("INSERT INTO feedback_digest (game_id, days, report_count, text, actor) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, gameId, days, count, text.strip(), actor);
        audit.save(new AuditLog(actor, "DIGEST_POST", "digest:" + id, (gameCode == null ? "전체" : gameCode) + " 최근 " + days + "일 제보 " + count + "건"));
        outbox.enqueue(NotificationType.DIGEST, Map.of("digestId", id));
        return id;
    }

    public record Digest(long id, String gameCode, String gameName, int days, int reportCount, String text, String actor, OffsetDateTime createdAt) {}

    @Transactional(readOnly = true)
    public Digest get(long id) {
        return jdbc.query("""
                SELECT d.id, g.code, g.name, d.days, d.report_count, d.text, d.actor, d.created_at
                FROM feedback_digest d LEFT JOIN game g ON g.id = d.game_id WHERE d.id = ?
                """, (rs, n) -> new Digest(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("정리 없음: " + id));
    }

    private Game game(String code) {
        return games.findByCode(code).orElseThrow(() -> new NotFoundException("등록되지 않은 게임: " + code));
    }
}
