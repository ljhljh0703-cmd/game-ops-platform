// 처리 지표(결정 35) — QA·개발 리드가 매주 보는 숫자를 기록에서 바로 계산한다. 따로 모으는 표 없이 제보·이슈·감사 기록만 쓴다.
// 고칠 때까지 = 이슈가 생긴 때 → 처음 「QA 확인 대기」나 「해결」이 된 때(고친 코드가 들어오거나 사람이 해결)
// QA 확인까지 = 이슈가 생긴 때 → 「해결」이 된 때(QA 확인을 쓰면 확인한 때)
// 숫자가 너무 적으면(3건 미만) 중앙값을 내지 않는다. 적은 수의 중앙값은 우연이 커서다
package com.ljh.gameops.metrics;

import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.game.GameSpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OpsMetrics {
    static final int MIN_SAMPLES = 3;

    private final JdbcTemplate jdbc;
    private final GameCatalog games;
    private final GameRepository gameRepo;
    private final Clock clock;

    public OpsMetrics(JdbcTemplate jdbc, GameCatalog games, GameRepository gameRepo, Clock clock) {
        this.jdbc = jdbc;
        this.games = games;
        this.gameRepo = gameRepo;
        this.clock = clock;
    }

    /**
     * @param toFix·toVerified 중앙값(표본이 적으면 null) · fixedSamples·verifiedSamples 표본 수
     * @param reports 기간 제보 수 · issues 기간에 새로 생긴 이슈 수 → 묶인 제보 = reports - issues
     * @param withRepro 재현 정보(버전·기기·재현 순서·스크린샷 중 하나라도)가 있는 제보 수
     * @param recurrences 기간에 생긴 이슈 중 재발로 표시된 수 · verifying 지금 QA 확인 대기
     */
    public record GameMetrics(String code, String name, int days, Duration toFix, int fixedSamples, Duration toVerified, int verifiedSamples,
                              long reports, long issues, long withRepro, long recurrences, long verifying) {
        public long merged() { return Math.max(0, reports - issues); }

        public int reproPercent() { return reports == 0 ? 0 : (int) Math.round(withRepro * 100.0 / reports); }

        public int recurrencePercent() { return issues == 0 ? 0 : (int) Math.round(recurrences * 100.0 / issues); }

        /** 슬랙 한 덩어리 */
        public String text() {
            return "*" + name + "* · 최근 " + days + "일\n"
                    + "• 고칠 때까지 " + dur(toFix, fixedSamples) + " · QA 확인까지 " + dur(toVerified, verifiedSamples) + "\n"
                    + "• 제보 " + reports + "건 → 이슈 " + issues + "건(같은 문제로 묶인 제보 " + merged() + "건)\n"
                    + "• 재현 정보 있는 제보 " + reproPercent() + "% · 재발 " + recurrences + "건(" + recurrencePercent() + "%) · 지금 QA 확인 대기 " + verifying + "건";
        }
    }

    @Transactional(readOnly = true)
    public List<GameMetrics> all(int days) {
        List<GameMetrics> out = new ArrayList<>();
        for (GameSpec g : games.all()) out.add(of(g.code(), days));
        return out;
    }

    @Transactional(readOnly = true)
    public GameMetrics of(String gameCode, int days) {
        long gameId = gameRepo.findByCode(gameCode).orElseThrow(() -> new com.ljh.gameops.common.NotFoundException("등록되지 않은 게임: " + gameCode)).getId();
        String name = games.byCode(gameCode).map(GameSpec::name).orElse(gameCode);
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(days);
        // 상태가 바뀐 때는 감사 기록(「OPEN->VERIFYING via …」)에서 읽는다
        double[] fix = median("""
                SELECT extract(epoch FROM (min(a.created_at) - i.created_at))
                FROM issue i JOIN audit_log a ON a.action = 'ISSUE_STATUS' AND a.target = 'issue:' || i.id
                 AND (a.detail LIKE '%->VERIFYING%' OR a.detail LIKE '%->RESOLVED%')
                WHERE i.game_id = ? AND a.created_at >= ?
                GROUP BY i.id, i.created_at
                """, gameId, since);
        double[] verified = median("""
                SELECT extract(epoch FROM (min(a.created_at) - i.created_at))
                FROM issue i JOIN audit_log a ON a.action = 'ISSUE_STATUS' AND a.target = 'issue:' || i.id AND a.detail LIKE '%->RESOLVED%'
                WHERE i.game_id = ? AND a.created_at >= ?
                GROUP BY i.id, i.created_at
                """, gameId, since);
        long reports = count("SELECT count(*) FROM report WHERE game_id = ? AND received_at >= ?", gameId, since);
        long withRepro = count("""
                SELECT count(*) FROM report WHERE game_id = ? AND received_at >= ?
                 AND (build_version IS NOT NULL OR device IS NOT NULL OR repro_steps IS NOT NULL OR attachment_urls IS NOT NULL)
                """, gameId, since);
        long issues = count("SELECT count(*) FROM issue WHERE game_id = ? AND created_at >= ?", gameId, since);
        long recur = count("SELECT count(*) FROM issue WHERE game_id = ? AND created_at >= ? AND recurrence_of IS NOT NULL", gameId, since);
        long verifying = jdbc.queryForObject("SELECT count(*) FROM issue WHERE game_id = ? AND status = 'VERIFYING'", Long.class, gameId);
        return new GameMetrics(gameCode, name, days, toDuration(fix), (int) fix[1], toDuration(verified), (int) verified[1],
                reports, issues, withRepro, recur, verifying);
    }

    /** [중앙값 초, 표본 수] */
    private double[] median(String sql, Object... args) {
        List<Double> xs = jdbc.queryForList(sql, Double.class, args).stream().filter(x -> x != null && x >= 0).sorted().toList();
        if (xs.isEmpty()) return new double[]{-1, 0};
        int n = xs.size();
        double m = n % 2 == 1 ? xs.get(n / 2) : (xs.get(n / 2 - 1) + xs.get(n / 2)) / 2;
        return new double[]{m, n};
    }

    private static Duration toDuration(double[] m) {
        return m[1] < MIN_SAMPLES ? null : Duration.ofSeconds(Math.round(m[0]));
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    /** 홈 현황판용 짧은 말: 「3시간 20분」 · 표본이 적으면 「기록 부족」 */
    public static String short_(Duration d, int samples) {
        if (d == null) return samples == 0 ? "기록 없음" : "기록 부족";
        String s = dur(d, samples);
        return s.substring(0, s.indexOf('('));
    }

    /** 「2일 4시간」「3시간 20분」「15분」. 표본이 적으면 「표본 2건이라 아직 안 냄」 */
    static String dur(Duration d, int samples) {
        if (d == null) return samples == 0 ? "기록 없음" : "표본 " + samples + "건이라 아직 안 냄";
        long m = d.toMinutes();
        String v = m >= 1440 ? (m / 1440) + "일 " + (m % 1440) / 60 + "시간" : m >= 60 ? (m / 60) + "시간 " + (m % 60) + "분" : Math.max(1, m) + "분";
        return v + "(중앙값, " + samples + "건)";
    }
}
