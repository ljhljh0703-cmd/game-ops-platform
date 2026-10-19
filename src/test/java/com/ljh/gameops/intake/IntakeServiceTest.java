// 제보 접수 — 중복 묶기·같은 메시지 재수신·심각도 상승·동시 접수
package com.ljh.gameops.intake;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeServiceTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired IssueRepository issues;
    @Autowired OutboxRepository outbox;

    private SubmitReport report(String body, String externalId, String author) {
        return new SubmitReport("gemtower", SourceChannel.DISCORD, externalId, author, body, null);
    }

    @Test
    void 같은_내용_제보_두_건은_이슈_하나로_묶인다() {
        IntakeResult a = intake.submit(report("5웨이브에서 게임이 튕겨요", "m1", "u1"));
        IntakeResult b = intake.submit(report("5 웨이브에서 게임이 튕겨요ㅠㅠ", "m2", "u2"));

        assertThat(a.outcome()).isEqualTo(IntakeResult.Outcome.NEW_ISSUE);
        assertThat(b.outcome()).isEqualTo(IntakeResult.Outcome.ATTACHED_TO_EXISTING);
        assertThat(b.issueId()).isEqualTo(a.issueId());
        assertThat(issues.count()).isEqualTo(1);
        assertThat(issues.findById(a.issueId()).orElseThrow().getReportCount()).isEqualTo(2);
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.NEW_ISSUE)).hasSize(1);
    }

    @Test
    void 다른_내용은_다른_이슈가_된다() {
        IntakeResult a = intake.submit(report("5웨이브에서 게임이 튕겨요", "m1", "u1"));
        IntakeResult b = intake.submit(report("루비 타워 공격력이 너무 약해요", "m2", "u2"));
        assertThat(b.issueId()).isNotEqualTo(a.issueId());
        assertThat(issues.count()).isEqualTo(2);
    }

    @Test
    void 같은_메시지가_다시_오면_제보를_새로_만들지_않는다() {
        IntakeResult a = intake.submit(report("보석 합성 버그 있어요", "m1", "u1"));
        IntakeResult again = intake.submit(report("보석 합성 버그 있어요", "m1", "u1"));
        assertThat(again.outcome()).isEqualTo(IntakeResult.Outcome.DUPLICATE_DELIVERY);
        assertThat(again.reportId()).isEqualTo(a.reportId());
        assertThat(jdbc.queryForObject("select count(*) from report", Long.class)).isEqualTo(1);
    }

    @Test
    void 더_심각한_제보가_붙으면_심각도를_올리고_알린다() {
        IntakeResult a = intake.submit(report("보석 합성 버그 있어요 합성 버튼 눌러도 안 돼요", "m1", "u1"));
        assertThat(issues.findById(a.issueId()).orElseThrow().getSeverity()).isEqualTo(Severity.MEDIUM);

        intake.submit(report("보석 합성 버그 있어요 합성 버튼 눌러도 안 돼요 튕겨요", "m2", "u2"));
        assertThat(issues.findById(a.issueId()).orElseThrow().getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.SEVERITY_RAISED)).hasSize(1);
    }

    @Test
    void 글자가_남지_않는_제보끼리는_묶지_않는다() {
        IntakeResult a = intake.submit(report("ㅋㅋㅋㅋ", "m1", "u1"));
        IntakeResult b = intake.submit(report("https://cdn.example.com/screenshot.png", "m2", "u2"));
        IntakeResult c = intake.submit(report("!!!", "m3", "u3"));
        assertThat(java.util.Set.of(a.issueId(), b.issueId(), c.issueId())).hasSize(3);
    }

    @Test
    void 작성자_ID는_원문으로_저장하지_않는다() {
        intake.submit(report("보석 합성 버그 있어요", "m1", "discord-user-42"));
        String stored = jdbc.queryForObject("select author_hash from report", String.class);
        assertThat(stored).doesNotContain("discord-user-42").hasSize(64);
    }

    @Test
    void 같은_내용이_동시에_들어와도_이슈는_하나다() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IntakeResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            futures.add(pool.submit(() -> {
                start.await();
                return intake.submit(report("맵 로딩 중에 멈춰요", "c" + k, "u" + k));
            }));
        }
        start.countDown();
        for (Future<IntakeResult> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(issues.count()).isEqualTo(1);
        assertThat(issues.findAll().get(0).getReportCount()).isEqualTo(n);
        assertThat(jdbc.queryForObject("select count(*) from issue_report", Long.class)).isEqualTo((long) n);
    }
}
