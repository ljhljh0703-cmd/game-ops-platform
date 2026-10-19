// 매주 정해진 시각에 게임마다 주간 보고를 알림 대기열에 넣는다
package com.ljh.gameops.report;

import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

@Component
public class WeeklyReportJob {
    private final GameRepository games;
    private final WeeklyReportService service;
    private final OutboxService outbox;
    private final Clock clock;
    private final ZoneId zone;

    public WeeklyReportJob(GameRepository games, WeeklyReportService service, OutboxService outbox, Clock clock,
                           @Value("${gameops.weekly-report.zone}") String zone) {
        this.games = games;
        this.service = service;
        this.outbox = outbox;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${gameops.weekly-report.cron}", zone = "${gameops.weekly-report.zone}")
    public void run() {
        enqueueAll();
    }

    /** 지난 7일(오늘 0시 기준)을 보고한다. 넣은 게임 수를 돌려준다. */
    public int enqueueAll() {
        ZonedDateTime today = ZonedDateTime.now(clock.withZone(zone)).toLocalDate().atStartOfDay(zone);
        OffsetDateTime to = today.toOffsetDateTime();
        OffsetDateTime from = today.minusDays(7).toOffsetDateTime();
        int n = 0;
        for (Game g : games.findAll()) {
            String text = service.build(g.getCode(), from, to);
            outbox.enqueueStandalone(NotificationType.WEEKLY_REPORT, Map.of("gameCode", g.getCode(), "text", text));
            n++;
        }
        return n;
    }
}
