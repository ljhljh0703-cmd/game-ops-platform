// 제보자에게 돌아가는 고리 — 디스코드 글의 답장 위치를 남기고, 해결하면 답장 알림을 대기열에 넣는다(보류는 안 넣음)
package com.ljh.gameops.channel;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.discord.DiscordInbound;
import com.ljh.gameops.channel.discord.DiscordReportHandler;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxDispatcher;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.notify.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerLoopTest extends IntegrationTest {
    @Autowired DiscordReportHandler handler;
    @Autowired IssueService issues;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxDispatcher dispatcher;

    private DiscordReportHandler.Reply post(String id, String body) {
        return handler.onChannelMessageReply(new DiscordInbound("msg:" + id, "C_REPORT", null, "user-" + id, false, null, body, null,
                "T_THREAD:" + id)).orElseThrow();
    }

    @Test
    void 디스코드_글은_답장_위치를_남기고_카드용_값을_돌려준다() {
        var r = post("11", "보스 웨이브에서 튕겨요");
        assertThat(r.kind()).isEqualTo(DiscordReportHandler.Reply.Kind.NEW);
        assertThat(r.category().name()).isEqualTo("CRASH");
        assertThat(r.gameName()).isNotBlank();
        assertThat(jdbc.queryForObject("select reply_to from report", String.class)).isEqualTo("T_THREAD:11");
    }

    @Test
    void 해결하면_제보자_답장이_대기열에_들어가고_보류는_안_들어간다() {
        long a = post("1", "보스 웨이브에서 튕겨요").issueId();
        long b = post("2", "루비 타워 공격력이 너무 약해요").issueId();
        issues.changeStatus(a, IssueStatus.RESOLVED, "t", "test");
        issues.changeStatus(a, IssueStatus.RESOLVED, "t", "test"); // 두 번 눌러도 한 번
        issues.changeStatus(b, IssueStatus.WONT_FIX, "t", "test");
        var queued = outbox.findByTypeOrderByIdAsc(NotificationType.PLAYER_RESOLVED);
        assertThat(queued).singleElement().satisfies(m -> assertThat(m.getPayload()).contains("\"issueId\":" + a));

        // 디스코드를 끈 시험 환경에서는 보낼 곳이 없으니 보낸 것으로 친다(쌓이지 않게)
        dispatcher.dispatchDue();
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.PLAYER_RESOLVED).get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void 명령으로_받은_제보는_오류도_카드_값으로() {
        var r = handler.onSlashCommandReply(new DiscordInbound("cmd:1", "C_X", null, "u", false, "없는게임", "튕겨요", null));
        assertThat(r.kind()).isEqualTo(DiscordReportHandler.Reply.Kind.ERROR);
        assertThat(r.text()).contains("등록되지 않은 게임");
    }
}
