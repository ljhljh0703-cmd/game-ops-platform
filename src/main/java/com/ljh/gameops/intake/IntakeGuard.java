// 도배·공격 막기(결정 36 · 43 · 44) — 디스코드 제보만. 근거와 남는 위험은 docs/SECURITY.md
//
// 신뢰 등급은 「기다린 시간」이 아니라 「한 일」로 매긴다. 계정 나이·서버 가입 기간은 미리 만들어 묵혀 둔 계정(잠복 계정)이
// 그냥 통과하므로 쓰지 않는다. 같은 규칙을 모든 계정에 똑같이 쓴다.
//   새 사람     실적 없음                                     10분에 3건
//   확인된 사람 제보가 사람이 다룬 이슈에 들어간 적 있음(1건 이상)  10분에 5건
//   믿는 사람   그런 이슈 3개 이상, 서로 다른 날 2일 이상           10분에 10건
//   우수 테스터 디스코드 「우수 테스터」 역할(운영자가 준다)           10분에 10건
//   운영진      운영진 역할                                    제한 없음
// 「사람이 다룬 이슈」 = 운영자가 작업 중·QA 확인 대기·해결로 옮긴 이슈. 이슈를 만든 것만으로는 실적이 아니다(누구나 만들 수 있으므로).
// 게임 하나에 1분 30건 넘게 몰리면(몰림 모드) 새 사람 제보는 버리지 않고 보류함에 받아 두었다가 몰림이 풀리면 차례로 접수한다.
package com.ljh.gameops.intake;

import com.ljh.gameops.game.Game;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import com.ljh.gameops.report.ReportRepository;
import com.ljh.gameops.report.SourceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class IntakeGuard {
    private static final Logger log = LoggerFactory.getLogger(IntakeGuard.class);

    public enum Reason { AUTHOR, FLOOD }

    public enum Level {
        NEW("새 사람"), CONFIRMED("확인된 사람"), TRUSTED("믿는 사람"), MEMBER("우수 테스터"), STAFF("운영진");
        public final String label;
        Level(String label) { this.label = label; }
    }

    /** 받기 · 보류함에 받아 두기 */
    public enum Decision { ACCEPT, HOLD }

    private final ReportRepository reports;
    private final AuthorHasher hasher;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;
    Clock clock = Clock.systemDefaultZone();

    @Value("${gameops.intake.limit-new:3}") int limitNew = 3;
    @Value("${gameops.intake.max-per-10min:5}") int limitConfirmed = 5;
    @Value("${gameops.intake.limit-trusted:10}") int limitTrusted = 10;
    @Value("${gameops.intake.trusted-issues:3}") int trustedIssues = 3;
    @Value("${gameops.intake.trusted-days:2}") int trustedDays = 2;
    @Value("${gameops.intake.flood-per-min:30}") int floodPerMin = 30;
    Set<String> memberRoles = Set.of();
    Set<String> staffRoles = Set.of();

    public IntakeGuard(ReportRepository reports, AuthorHasher hasher, OutboxService outbox, JdbcTemplate jdbc,
                       @Value("${gameops.intake.member-roles:}") String memberRoles,
                       @Value("${gameops.intake.staff-roles:}") String staffRoles) {
        this.reports = reports;
        this.hasher = hasher;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.memberRoles = roles(memberRoles);
        this.staffRoles = roles(staffRoles);
    }

    /** 「후원자, 1234567890」 → 이름은 소문자로. 역할 ID·이름 둘 다 받는다 */
    static Set<String> roles(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean has(List<String> mine, Set<String> wanted) {
        if (wanted.isEmpty()) return false;
        for (String r : mine) if (wanted.contains(r.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    public Level level(SubmitReport cmd) {
        if (has(cmd.authorRoles(), staffRoles)) return Level.STAFF;
        String hash = hasher.hash(cmd.channel().name(), cmd.authorId());
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(DISTINCT i.id) AS issues, count(DISTINCT (r.received_at AT TIME ZONE 'Asia/Seoul')::date) AS days
                FROM report r JOIN issue_report l ON l.report_id = r.id JOIN issue i ON i.id = l.issue_id
                WHERE r.author_hash = ? AND i.status IN ('IN_PROGRESS', 'VERIFYING', 'RESOLVED')""", hash);
        long issues = ((Number) row.get("issues")).longValue(), days = ((Number) row.get("days")).longValue();
        if (issues >= trustedIssues && days >= trustedDays) return Level.TRUSTED;
        if (has(cmd.authorRoles(), memberRoles)) return Level.MEMBER;
        return issues >= 1 ? Level.CONFIRMED : Level.NEW;
    }

    int limit(Level l) {
        return switch (l) {
            case NEW -> limitNew;
            case CONFIRMED -> limitConfirmed;
            case TRUSTED, MEMBER -> limitTrusted;
            case STAFF -> 0;
        };
    }

    public boolean flooding(Game game) {
        if (floodPerMin <= 0) return false;
        return reports.countByGameIdAndSourceChannelAndReceivedAtAfter(game.getId(), SourceChannel.DISCORD,
                OffsetDateTime.now(clock).minusMinutes(1)) >= floodPerMin;
    }

    /**
     * 받기·보류 결정. 한도를 넘으면 RateLimitedException.
     * @param released 보류함에서 꺼낸 제보면 true(몰림 검사는 건너뛰고 한도만 본다)
     */
    public Decision check(Game game, SubmitReport cmd, boolean released) {
        if (cmd.channel() != SourceChannel.DISCORD) return Decision.ACCEPT;
        Level level = level(cmd);
        int limit = limit(level);
        if (limit > 0) {
            String hash = hasher.hash(cmd.channel().name(), cmd.authorId());
            OffsetDateTime since = OffsetDateTime.now(clock).minusMinutes(10);
            long recent = reports.countByAuthorHashAndReceivedAtAfter(hash, since)
                    + (released ? 0 : heldSince(hash, since));
            if (recent >= limit) {
                log.info("도배 막기: {} 은 10분에 {}건까지", level.label, limit);
                throw new RateLimitedException(Reason.AUTHOR,
                        "짧은 시간에 제보가 많아요. 10분쯤 뒤에 다시 올려 주세요. 같은 문제면 처음 글에 답글로 더해 주시면 돼요.");
            }
        }
        if (!released && level == Level.NEW && flooding(game)) {
            alertFlood(game);
            return Decision.HOLD;
        }
        return Decision.ACCEPT;
    }

    private long heldSince(String hash, OffsetDateTime since) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM held_report WHERE author_hash = ? AND received_at >= ?", Long.class, hash, since);
        return n == null ? 0 : n;
    }

    /** 몰림 알림은 게임마다 10분에 한 번 */
    private void alertFlood(Game game) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String bucket = now.toLocalDate() + "T" + now.getHour() + ":" + (now.getMinute() / 10);
        String text = "🚨 [" + game.getName() + "] 디스코드 제보가 1분에 " + floodPerMin + "건 넘게 몰리고 있어요. "
                + "실적 없는 사람의 제보는 보류함에 받아 두고, 몰림이 풀리면 차례로 접수합니다(잃는 제보 없음). "
                + "실적 있는 사람·우수 테스터 제보는 그대로 받고 있어요. 진짜 큰 버그인지 공격인지 카드에서 확인해 주세요.";
        try {
            outbox.enqueueOnce(NotificationType.OPS_ALERT, Map.of("text", text), "flood:" + game.getCode() + ":" + bucket);
        } catch (RuntimeException e) {
            log.warn("몰림 알림 실패: {}", e.getMessage());
        }
    }
}
