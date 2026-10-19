// 주간 보고 — 가짜 이슈로 만든 문구가 기대와 같은지, 게임마다 대기열에 들어가는지
package com.ljh.gameops.report;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.intake.IntakeResult;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxDispatcher;
import com.ljh.gameops.notify.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyReportTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired IssueService issues;
    @Autowired WeeklyReportService service;
    @Autowired WeeklyReportJob job;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxDispatcher dispatcher;

    private IntakeResult submit(String ext, String body) {
        return intake.submit(new SubmitReport("gemtower", com.ljh.gameops.report.SourceChannel.API, ext, "u-" + ext, body, null));
    }

    @Test
    void 주간_보고_문구() {
        IntakeResult crash = submit("1", "보스 웨이브에서 튕겨요");
        submit("2", "보스웨이브에서 튕겨요ㅠㅠ");
        submit("3", "보스 웨이브에서 계속 튕겨요");
        IntakeResult balance = submit("4", "루비 타워 공격력이 너무 약해요");
        IntakeResult ui = submit("5", "합성 버튼 글씨가 너무 작아서 불편해요");
        issues.changeStatus(ui.issueId(), IssueStatus.RESOLVED, "t", "test");

        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1);
        String text = service.build("gemtower", to.minusDays(7), to);

        assertThat(text).startsWith("[보석타워] 주간 운영 보고");
        assertThat(text).contains("신규 이슈 3건 · 해결 1건 · 미해결 2건");
        // 제보가 많은 순: 튕김(3건) → 밸런스(1건)
        assertThat(text.indexOf("#" + crash.issueId())).isLessThan(text.indexOf("#" + balance.issueId()));
        assertThat(text).contains("제보 3건").doesNotContain("#" + ui.issueId() + " ");
    }

    @Test
    void 이슈가_없으면_없다고_쓴다() {
        OffsetDateTime to = OffsetDateTime.now();
        assertThat(service.build("jelly-panic", to.minusDays(7), to)).contains("신규 이슈 0건").contains("미해결 이슈 없음");
    }

    @Test
    void 게임마다_대기열에_넣고_보낸다() {
        assertThat(job.enqueueAll()).isEqualTo(2);
        assertThat(job.enqueueAll()).isZero(); // 서버가 두 대라 같은 작업이 또 돌아도 같은 주 보고는 한 번만
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.WEEKLY_REPORT)).hasSize(2);
        dispatcher.dispatchDue();
        assertThat(sender.sent).extracting(n -> n.text()).anyMatch(t -> t.startsWith("[보석타워]"))
                .anyMatch(t -> t.startsWith("[젤리 패닉]"));
    }
}
