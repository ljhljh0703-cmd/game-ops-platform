// 처리 지표(결정 35) — 고칠 때까지·QA 확인까지 중앙값(표본 3건 이상), 묶인 제보, 재현 정보 비율, 재발, QA 대기.
// 주간 보고·홈 현황판·API·슬랙 명령에 같은 숫자
package com.ljh.gameops.metrics;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackHomeService;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.report.ReproInfo;
import com.ljh.gameops.report.SourceChannel;
import com.ljh.gameops.report.WeeklyReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OpsMetricsTest extends IntegrationTest {
    @Autowired OpsMetrics metrics;
    @Autowired IntakeService intake;
    @Autowired IssueService issues;
    @Autowired WeeklyReportService weekly;
    @Autowired SlackHomeService home;

    private long issue(String body, ReproInfo repro, int hoursAgo) {
        long id = intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a" + body.hashCode(), body, null, null, repro)).issueId();
        jdbc.update("UPDATE issue SET created_at = now() - make_interval(hours => ?) WHERE id = ?", hoursAgo, id);
        return id;
    }

    @Test
    void 고칠_때까지와_QA_확인까지_중앙값() {
        long a = issue("보스 웨이브에서 튕겨요", ReproInfo.of("1.2", "아이폰", null), 1);
        long b = issue("결제했는데 보석이 안 들어와요", null, 2);
        long c = issue("타워가 안 지어져요 버그", null, 3);
        issue("랭킹이 안 떠요 버그", null, 4); // 아직 안 고침
        for (long id : new long[]{a, b, c}) issues.changeStatus(id, IssueStatus.VERIFYING, "github:godju", "github-pr#1");
        issues.changeStatus(a, IssueStatus.RESOLVED, "slack:UQA", "slack");

        var m = metrics.of("gemtower", 30);
        assertThat(m.toFix()).isBetween(Duration.ofMinutes(119), Duration.ofMinutes(121)); // 1·2·3시간의 중앙값
        assertThat(m.fixedSamples()).isEqualTo(3);
        assertThat(m.toVerified()).isNull(); // 확인은 1건이라 안 냄
        assertThat(m.verifiedSamples()).isEqualTo(1);
        assertThat(m.verifying()).isEqualTo(2);
        assertThat(m.reports()).isEqualTo(4);
        assertThat(m.reproPercent()).isEqualTo(25);
        assertThat(m.text()).contains("고칠 때까지 2시간 0분(중앙값, 3건)", "QA 확인까지 표본 1건이라 아직 안 냄", "지금 QA 확인 대기 2건");
    }

    @Test
    void 묶인_제보와_재발() {
        long first = issue("보스 웨이브에서 튕겨요", null, 1);
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "b", "보스웨이브에서 튕겨요ㅠ", null));
        issues.changeStatus(first, IssueStatus.RESOLVED, "slack:U", "slack");
        intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "c", "보스 웨이브에서 또 튕겨요", null));

        var m = metrics.of("gemtower", 30);
        assertThat(m.reports()).isEqualTo(3);
        assertThat(m.issues()).isEqualTo(2);
        assertThat(m.merged()).isEqualTo(1);
        assertThat(m.recurrences()).isEqualTo(1);
        assertThat(m.recurrencePercent()).isEqualTo(50);
    }

    @Test
    void 주간_보고와_홈_현황판에_들어간다() {
        issue("보스 웨이브에서 튕겨요", null, 1);
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(weekly.build("gemtower", now.minusDays(7), now.plusMinutes(1))).contains("처리 지표:", "재현 정보 있는 제보 0%");
        var board = home.board();
        assertThat(board.games()).anySatisfy(g -> assertThat(g.metrics()).contains("최근 30일 · 고칠 때까지 기록 없음 · 재현 정보 0%"));
        assertThat(board.usage()).startsWith("이번 달 AI 약 $0.0000");
    }

    @Test
    void 시간_표시() {
        assertThat(OpsMetrics.dur(Duration.ofMinutes(3 * 1440 + 125), 4)).isEqualTo("3일 2시간(중앙값, 4건)");
        assertThat(OpsMetrics.dur(Duration.ofSeconds(20), 3)).isEqualTo("1분(중앙값, 3건)");
        assertThat(OpsMetrics.dur(null, 0)).isEqualTo("기록 없음");
    }
}
