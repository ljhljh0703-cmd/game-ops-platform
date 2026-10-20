// 재발 표시 — 최근 해결한 이슈와 비슷한 새 제보는 새 이슈로 만들되 재발 의심을 적는다
package com.ljh.gameops.intake;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.discord.DiscordInbound;
import com.ljh.gameops.channel.discord.DiscordReportHandler;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.notify.NotificationRenderer;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired IssueService issueService;
    @Autowired IssueRepository issues;
    @Autowired OutboxRepository outbox;
    @Autowired NotificationRenderer renderer;
    @Autowired DiscordReportHandler discord;

    private IntakeResult submit(String body) {
        return intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "p", body, null));
    }

    private void close(long issueId, IssueStatus status) {
        issueService.changeStatus(issueId, status, "tester", "test");
    }

    @Test
    void 해결한_버그가_다시_제보되면_새_이슈에_재발_의심을_적고_알림에도_쓴다() {
        IntakeResult first = submit("보스 웨이브에서 튕겨요");
        close(first.issueId(), IssueStatus.RESOLVED);

        IntakeResult again = submit("보스웨이브에서 또 튕겨요ㅠ");
        assertThat(again.outcome()).isEqualTo(IntakeResult.Outcome.NEW_ISSUE);
        var issue = issues.findById(again.issueId()).orElseThrow();
        assertThat(issue.getRecurrenceOf()).isEqualTo(first.issueId());
        assertThat(issue.getRecurrenceSimilarity()).isGreaterThanOrEqualTo(0.55);

        var msg = outbox.findAll().stream()
                .filter(m -> m.getPayload().contains("\"issueId\":" + again.issueId())).findFirst().orElseThrow();
        assertThat(renderer.render(msg).text()).contains("재발 의심").contains("#" + first.issueId());
    }

    @Test
    void 디스코드_답장에도_재발을_알린다() {
        IntakeResult first = submit("보스 웨이브에서 튕겨요");
        close(first.issueId(), IssueStatus.RESOLVED);
        var reply = discord.onChannelMessage(new DiscordInbound("msg:r1", "C_REPORT", "버그-제보", "u", false, "jelly-panic", "보스 웨이브에서 튕겨요", null));
        assertThat(reply).hasValueSatisfying(r -> assertThat(r).contains("다시 생긴 것 같아").contains("#" + first.issueId()));
    }

    @Test
    void 기간이_지난_해결_이슈와_고치지_않기로_한_이슈는_재발로_보지_않는다() {
        IntakeResult old = submit("보스 웨이브에서 튕겨요");
        close(old.issueId(), IssueStatus.RESOLVED);
        jdbc.update("update issue set resolved_at = now() - interval '40 days' where id = ?", old.issueId());
        IntakeResult wont = submit("젤리 색이 너무 어두워요");
        close(wont.issueId(), IssueStatus.WONT_FIX);

        assertThat(issues.findById(submit("보스 웨이브에서 튕겨요").issueId()).orElseThrow().getRecurrenceOf()).isNull();
        assertThat(issues.findById(submit("젤리 색이 너무 어두워요").issueId()).orElseThrow().getRecurrenceOf()).isNull();
    }

    @Test
    void 관계없는_새_제보에는_표시하지_않는다() {
        IntakeResult first = submit("보스 웨이브에서 튕겨요");
        close(first.issueId(), IssueStatus.RESOLVED);
        assertThat(issues.findById(submit("결제했는데 젤리가 안 들어와요").issueId()).orElseThrow().getRecurrenceOf()).isNull();
    }

    @Test
    void 열린_이슈가_있으면_거기에_붙고_재발로_보지_않는다() {
        IntakeResult first = submit("보스 웨이브에서 튕겨요");
        close(first.issueId(), IssueStatus.RESOLVED);
        IntakeResult second = submit("보스 웨이브에서 튕겨요");   // 재발 이슈
        IntakeResult third = submit("보스 웨이브에서 튕겨요");    // 열린 재발 이슈에 붙는다
        assertThat(third.issueId()).isEqualTo(second.issueId());
        assertThat(third.outcome()).isEqualTo(IntakeResult.Outcome.ATTACHED_TO_EXISTING);
    }
}
