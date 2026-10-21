// Jira 티켓 기록(V12 jira_ticket) — 운영 이슈 하나에 티켓 하나. 카드·보고서·감시가 같은 곳에서 읽는다
package com.ljh.gameops.jira;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JiraTickets {
    public record Ticket(long issueId, String key, String url, Long digestId, boolean done) {}

    private final JdbcTemplate jdbc;

    public JiraTickets(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Ticket> of(long issueId) {
        return jdbc.query("SELECT issue_id, jira_key, url, digest_id, done_at IS NOT NULL FROM jira_ticket WHERE issue_id = ?",
                (rs, n) -> new Ticket(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), rs.getBoolean(5)), issueId)
                .stream().findFirst();
    }

    public List<Ticket> forDigest(long digestId) {
        return jdbc.query("SELECT issue_id, jira_key, url, digest_id, done_at IS NOT NULL FROM jira_ticket WHERE digest_id = ? ORDER BY issue_id",
                (rs, n) -> new Ticket(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), rs.getBoolean(5)), digestId);
    }

    public List<Ticket> notDone() {
        return jdbc.query("SELECT issue_id, jira_key, url, digest_id, false FROM jira_ticket WHERE done_at IS NULL ORDER BY issue_id",
                (rs, n) -> new Ticket(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), false));
    }

    /** 처음 만든 티켓이면 true. 이미 있으면 false(두 번 만들지 않음) */
    public boolean save(long issueId, String key, String url, Long digestId, String actor) {
        return jdbc.update("INSERT INTO jira_ticket (issue_id, jira_key, url, digest_id, actor) VALUES (?, ?, ?, ?, ?) ON CONFLICT (issue_id) DO NOTHING",
                issueId, key, url, digestId, actor) == 1;
    }

    /** 완료 표시. 이번에 처음 완료로 바꿨으면 true */
    public boolean markDone(long issueId) {
        return jdbc.update("UPDATE jira_ticket SET done_at = now() WHERE issue_id = ? AND done_at IS NULL", issueId) == 1;
    }
}
