// 공격 막기(결정 43) — 새 계정은 10분에 1건, 게임에 제보가 몰리면 새 계정만 막고 오래된 계정은 받는다, 몰림 알림은 한 번
package com.ljh.gameops.intake;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntakeGuardTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired IntakeGuard guard;
    @Autowired OutboxRepository outbox;

    /** 이만큼 전에 만든 디스코드 계정의 ID */
    static String discordId(Duration age) {
        long ms = Instant.now().minus(age).toEpochMilli() - IntakeGuard.DISCORD_EPOCH;
        return String.valueOf(ms << 22);
    }

    private IntakeResult submit(String author, String body, String id) {
        return intake.submit(new SubmitReport("gemtower", SourceChannel.DISCORD, id, author, body, null));
    }

    @Test
    void 디스코드_ID로_계정_나이를_잰다() {
        assertThat(guard.accountAge(discordId(Duration.ofDays(3))).toDays()).isEqualTo(3);
        assertThat(guard.accountAge("web-1")).isNull(); // ID 모양이 아니면 모름 → 새 계정으로 보지 않는다
    }

    @Test
    void 새_계정은_10분에_1건() {
        String fresh = discordId(Duration.ofDays(2));
        submit(fresh, "5웨이브에서 게임이 튕겨요", "n1");
        assertThatThrownBy(() -> submit(fresh, "보스가 너무 쎄요", "n2"))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> assertThat(e.reason()).isEqualTo(IntakeGuard.Reason.NEW_ACCOUNT));
        String old = discordId(Duration.ofDays(400));
        submit(old, "5웨이브에서 게임이 튕겨요", "o1");
        submit(old, "보스가 너무 쎄요", "o2"); // 오래된 계정은 5건까지
    }

    @Test
    void 몰림_모드는_새_계정만_막고_오래된_계정은_받는다() {
        int before = guard.floodPerMin;
        guard.floodPerMin = 6;
        try {
            for (int i = 0; i < 6; i++) submit(discordId(Duration.ofDays(500 + i)), "5웨이브에서 게임이 튕겨요", "f" + i);
            String month = discordId(Duration.ofDays(20));
            assertThatThrownBy(() -> submit(month, "5웨이브에서 게임이 튕겨요", "f-new"))
                    .isInstanceOfSatisfying(RateLimitedException.class, e -> assertThat(e.reason()).isEqualTo(IntakeGuard.Reason.FLOOD));
            IntakeResult real = submit(discordId(Duration.ofDays(900)), "5웨이브에서 게임이 튕겨요", "f-old");
            assertThat(real.outcome()).isEqualTo(IntakeResult.Outcome.ATTACHED_TO_EXISTING);
            // 몰리는 동안 알림은 한 번
            assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.OPS_ALERT)).hasSize(1)
                    .first().satisfies(o -> assertThat(o.getPayload()).contains("몰림 모드"));
        } finally {
            guard.floodPerMin = before;
        }
    }

    @Test
    void 슬랙_API_제보는_막지_않는다() {
        for (int i = 0; i < 8; i++) intake.submit(new SubmitReport("gemtower", SourceChannel.API, "a" + i, "ops", "5웨이브 튕김 " + i, null));
    }
}
