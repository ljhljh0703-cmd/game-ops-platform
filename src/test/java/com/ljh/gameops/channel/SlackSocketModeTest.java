// 슬랙 소켓 모드(결정 40) — 소켓으로 받은 명령·버튼·이벤트가 HTTP 로 받을 때와 같은 처리기로 가고, 처리기 답이 그대로 슬랙 답이 된다.
// 앱 토큰이 없으면 꺼져 있다(예전 주소 방식)
package com.ljh.gameops.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackSocketMode;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSocketModeTest extends IntegrationTest {
    @Autowired SlackSocketMode socket;
    @Autowired ObjectMapper mapper;
    @Autowired IntakeService intake;

    private Object dispatch(String type, String json) throws Exception {
        Method m = SlackSocketMode.class.getDeclaredMethod("dispatch", String.class, com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        return m.invoke(socket, type, mapper.readTree(json));
    }

    @Test
    void 토큰이_없으면_꺼져_있다() {
        assertThat(socket.enabled()).isFalse();
    }

    @Test
    void 명령은_같은_처리기로_답은_그대로() throws Exception {
        Object r = dispatch("slash_commands", "{\"command\":\"/피드백정리\",\"text\":\"사용량\",\"user_id\":\"U_ADMIN\",\"channel_id\":\"C\",\"response_url\":\"https://example.com/x\"}");
        assertThat(((Map<?, ?>) r).get("response_type")).isEqualTo("ephemeral");
        assertThat(((Map<?, ?>) r).get("text").toString()).contains("이번 달 AI");
        Object denied = dispatch("slash_commands", "{\"text\":\"사용량\",\"user_id\":\"U_STRANGER\"}");
        assertThat(((Map<?, ?>) denied).get("text").toString()).contains("운영 담당만");
    }

    @Test
    void 버튼도_같은_처리기로_상태가_바뀐다() throws Exception {
        long id = intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null)).issueId();
        dispatch("interactive", """
                {"type":"block_actions","user":{"id":"U_ADMIN"},"actions":[{"action_id":"issue_status_in_progress","value":"%d:IN_PROGRESS"}],
                 "container":{"channel_id":"C1","message_ts":"1.1"}}""".formatted(id));
        assertThat(jdbc.queryForObject("SELECT status FROM issue WHERE id = ?", String.class, id)).isEqualTo(IssueStatus.IN_PROGRESS.name());
        assertThat(dispatch("events_api", "{\"event\":{\"type\":\"app_home_opened\",\"user\":\"U_ADMIN\",\"tab\":\"home\"}}")).isNull();
    }
}
