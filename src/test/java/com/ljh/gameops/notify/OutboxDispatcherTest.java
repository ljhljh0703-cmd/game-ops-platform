// 알림 대기열 — 전송이 실패해도 제보·이슈는 남고, 다시 보내고, 끝내 안 되면 FAILED
package com.ljh.gameops.notify;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDispatcherTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRepository outbox;

    private void submit() {
        intake.submit(new SubmitReport("gemtower", SourceChannel.DISCORD, "m1", "u1", "5웨이브에서 튕겨요", null));
    }

    private void makeDue() {
        jdbc.update("update notification_outbox set next_attempt_at = now() - interval '1 second'");
    }

    @Test
    void 전송에_성공하면_SENT() {
        submit();
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).text()).contains("보석타워").contains("새 이슈");
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void 슬랙이_실패해도_제보는_남고_다음_차례에_다시_보낸다() {
        submit();
        sender.failNext.set(1);

        assertThat(dispatcher.dispatchDue()).isZero();
        OutboxMessage m = outbox.findAll().get(0);
        assertThat(m.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(m.getAttempts()).isEqualTo(1);
        assertThat(m.getLastError()).contains("503");
        assertThat(jdbc.queryForObject("select count(*) from report", Long.class)).isEqualTo(1);

        makeDue();
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void 다섯_번_실패하면_FAILED로_멈춘다() {
        submit();
        sender.failNext.set(100);
        for (int i = 0; i < 5; i++) {
            makeDue();
            dispatcher.dispatchDue();
        }
        OutboxMessage m = outbox.findAll().get(0);
        assertThat(m.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(m.getAttempts()).isEqualTo(5);
        makeDue();
        assertThat(dispatcher.dispatchDue()).isZero();
    }
}
