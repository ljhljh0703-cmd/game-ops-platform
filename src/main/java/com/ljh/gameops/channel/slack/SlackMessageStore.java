// 슬랙에 올린 카드 위치 기억하기(V8 slack_message) — 이슈 카드·규칙 카드·피드백 정리 카드. 한 대상에 카드가 여러 장일 수 있다
package com.ljh.gameops.channel.slack;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SlackMessageStore {
    public enum Kind { ISSUE, RULES, DIGEST, PATCH }

    public record Location(String channel, String ts, String lastAction) {}

    private final JdbcTemplate jdbc;

    public SlackMessageStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 카드를 올렸거나(보낼 때), 기억에 없는 카드의 버튼을 눌렀을 때(이 기능 전에 올린 카드). 이미 있으면 그대로 */
    public void remember(Kind kind, long refId, String channel, String ts) {
        if (channel == null || ts == null) return;
        jdbc.update("""
                INSERT INTO slack_message (kind, ref_id, channel, ts) VALUES (?, ?, ?, ?)
                ON CONFLICT (kind, ref_id, ts) DO NOTHING
                """, kind.name(), refId, channel, ts);
    }

    public void setLastAction(Kind kind, long refId, String lastAction) {
        jdbc.update("UPDATE slack_message SET last_action = ?, updated_at = now() WHERE kind = ? AND ref_id = ?",
                lastAction, kind.name(), refId);
    }

    public List<Location> find(Kind kind, long refId) {
        return jdbc.query("SELECT channel, ts, last_action FROM slack_message WHERE kind = ? AND ref_id = ? ORDER BY updated_at",
                (rs, i) -> new Location(rs.getString(1), rs.getString(2), rs.getString(3)), kind.name(), refId);
    }
}
