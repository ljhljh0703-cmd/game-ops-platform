// 보류함 비우기(결정 44) — 30초마다, 몰림이 풀린 게임의 보류 제보를 오래된 것부터 20건씩 보통 접수로 넘긴다.
// 넘길 때도 한 사람 한도는 그대로 본다(보류함으로 도배를 미뤄 두는 길을 막는다). 넘기고 나면 보류함에서 지운다(디스코드 ID 를 오래 두지 않게).
package com.ljh.gameops.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HeldReleaser {
    private static final Logger log = LoggerFactory.getLogger(HeldReleaser.class);
    static final int BATCH = 20;

    private final JdbcTemplate jdbc;
    private final IntakeService intake;
    private final IntakeGuard guard;
    private final GameRepository games;
    private final ObjectMapper mapper;

    public HeldReleaser(JdbcTemplate jdbc, IntakeService intake, IntakeGuard guard, GameRepository games, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.intake = intake;
        this.guard = guard;
        this.games = games;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${gameops.intake.held-release-ms:30000}", initialDelay = 30000)
    public void tick() {
        release();
    }

    /** 넘긴 건수 */
    public int release() {
        int done = 0;
        for (Long gameId : jdbc.queryForList("SELECT DISTINCT game_id FROM held_report", Long.class)) {
            Game game = games.findById(gameId).orElse(null);
            if (game == null || guard.flooding(game)) continue;
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, payload FROM held_report WHERE game_id = ? ORDER BY id LIMIT " + BATCH, gameId);
            for (Map<String, Object> r : rows) {
                long id = ((Number) r.get("id")).longValue();
                try {
                    intake.submitReleased(mapper.readValue((String) r.get("payload"), SubmitReport.class));
                    done++;
                } catch (RateLimitedException e) {
                    log.info("보류 제보가 한 사람 한도에 걸려 버림 id={}", id);
                } catch (Exception e) {
                    log.warn("보류 제보 접수 실패 id={}: {}", id, e.getMessage());
                }
                jdbc.update("DELETE FROM held_report WHERE id = ?", id);
            }
        }
        if (done > 0) log.info("보류함에서 {}건 접수", done);
        return done;
    }
}
