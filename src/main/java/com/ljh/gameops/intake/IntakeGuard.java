// 도배·공격 막기(결정 36 · 43) — 디스코드 제보만. 누가 무엇을 막는지는 docs/SECURITY.md
//  ① 한 사람: 10분에 5건(가입 7일 안 된 계정은 1건)
//  ② 게임 하나에 1분 30건 넘게 몰리면 「몰림 모드」: 가입 30일 안 된 계정 제보는 받지 않고, 슬랙 긴급 채널에 한 번 알린다
//     진짜 큰 버그도 제보가 몰린다. 그래서 몰림 모드에서도 오래된 계정 제보는 그대로 받는다(진짜 신고를 잃지 않게)
// 계정 나이는 디스코드 사용자 ID 안에 든 만든 시각으로 잰다(디스코드에 묻지 않음). 작성자는 여전히 해시로만 센다.
package com.ljh.gameops.intake;

import com.ljh.gameops.game.Game;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import com.ljh.gameops.report.ReportRepository;
import com.ljh.gameops.report.SourceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

@Component
public class IntakeGuard {
    private static final Logger log = LoggerFactory.getLogger(IntakeGuard.class);
    /** 디스코드 ID 의 시각 기준(2015-01-01) */
    static final long DISCORD_EPOCH = 1420070400000L;

    public enum Reason { AUTHOR, NEW_ACCOUNT, FLOOD }

    private final ReportRepository reports;
    private final AuthorHasher hasher;
    private final OutboxService outbox;
    Clock clock = Clock.systemDefaultZone();

    @Value("${gameops.intake.max-per-10min:5}") int maxPer10Min = 5;
    @Value("${gameops.intake.new-account-days:7}") int newAccountDays = 7;
    @Value("${gameops.intake.new-account-max-per-10min:1}") int newAccountMax = 1;
    @Value("${gameops.intake.flood-per-min:30}") int floodPerMin = 30;
    @Value("${gameops.intake.flood-min-account-days:30}") int floodMinAccountDays = 30;

    public IntakeGuard(ReportRepository reports, AuthorHasher hasher, OutboxService outbox) {
        this.reports = reports;
        this.hasher = hasher;
        this.outbox = outbox;
    }

    /** 디스코드 사용자 ID 로 계정 나이. ID 모양이 아니면 null(나이를 모르면 새 계정으로 보지 않는다) */
    Duration accountAge(String authorId) {
        try {
            long id = Long.parseLong(authorId);
            if (id <= 0) return null;
            Instant created = Instant.ofEpochMilli((id >> 22) + DISCORD_EPOCH);
            Duration age = Duration.between(created, clock.instant());
            return age.isNegative() ? null : age;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 받으면 그대로 돌아오고, 막으면 RateLimitedException(이유와 함께) */
    public void check(Game game, SubmitReport cmd) {
        if (cmd.channel() != SourceChannel.DISCORD) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration age = accountAge(cmd.authorId());

        if (floodPerMin > 0) {
            long lastMinute = reports.countByGameIdAndSourceChannelAndReceivedAtAfter(game.getId(), SourceChannel.DISCORD, now.minusMinutes(1));
            if (lastMinute >= floodPerMin) {
                alertFlood(game, lastMinute, now);
                if (age != null && age.toDays() < floodMinAccountDays) {
                    log.info("몰림 모드: 가입 {}일 안 된 계정 제보를 받지 않음 game={}", floodMinAccountDays, game.getCode());
                    throw new RateLimitedException(Reason.FLOOD, "지금 같은 게임에 제보가 몰려 확인 중이에요. 잠시 뒤 다시 올려 주세요.");
                }
            }
        }

        boolean fresh = age != null && age.toDays() < newAccountDays;
        int limit = fresh ? newAccountMax : maxPer10Min;
        if (limit > 0) {
            long recent = reports.countByAuthorHashAndReceivedAtAfter(hasher.hash(cmd.channel().name(), cmd.authorId()), now.minusMinutes(10));
            if (recent >= limit) {
                log.info("도배 막기: 10분에 {}건 넘게 올린 {}작성자의 제보를 받지 않음", limit, fresh ? "새 계정 " : "");
                throw new RateLimitedException(fresh ? Reason.NEW_ACCOUNT : Reason.AUTHOR,
                        "짧은 시간에 제보가 많아요. 10분쯤 뒤에 다시 올려 주세요. 같은 문제면 처음 글에 답글로 더해 주시면 돼요.");
            }
        }
    }

    /** 몰림 알림은 게임마다 10분에 한 번 */
    private void alertFlood(Game game, long lastMinute, OffsetDateTime now) {
        String bucket = now.toLocalDate() + "T" + now.getHour() + ":" + (now.getMinute() / 10);
        String text = "🚨 [" + game.getName() + "] 디스코드 제보가 1분에 " + lastMinute + "건 몰리고 있어요. 몰림 모드: 가입 "
                + floodMinAccountDays + "일 안 된 계정 제보는 받지 않고, 오래된 계정 제보는 그대로 받습니다. "
                + "진짜 큰 버그인지(서로 다른 사람·비슷한 글) 공격인지(새 계정·같은 글) 슬랙 카드에서 확인해 주세요.";
        try {
            outbox.enqueueOnce(NotificationType.OPS_ALERT, Map.of("text", text), "flood:" + game.getCode() + ":" + bucket);
        } catch (RuntimeException e) {
            log.warn("몰림 알림 실패: {}", e.getMessage());
        }
    }
}
