// 슬랙 전송 — 슬랙이 ok=false 를 주거나 네트워크가 끊기면 예외로 대기열 재시도에 넘긴다
package com.ljh.gameops.channel;

import com.ljh.gameops.channel.slack.SlackMessageBuilder;
import com.ljh.gameops.channel.slack.SlackProperties;
import com.ljh.gameops.channel.slack.SlackSender;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.RenderedNotification;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SlackSenderTest {
    private final MethodsClient client = mock(MethodsClient.class);
    private final SlackSender sender = new SlackSender(client, new SlackMessageBuilder(
            new SlackProperties(true, "x", "s", new SlackProperties.Channels("C_DEFAULT", "C_URGENT"))));
    private final RenderedNotification n = new RenderedNotification(NotificationType.NEW_ISSUE, Severity.HIGH, 3L, "새 이슈 #3");

    private ChatPostMessageResponse response(boolean ok, String error) {
        ChatPostMessageResponse r = new ChatPostMessageResponse();
        r.setOk(ok);
        r.setError(error);
        return r;
    }

    @Test
    void 성공하면_긴급_채널로_보낸다() throws Exception {
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response(true, null));
        sender.send(n);
        ArgumentCaptor<ChatPostMessageRequest> cap = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(client).chatPostMessage(cap.capture());
        assertThat(cap.getValue().getChannel()).isEqualTo("C_URGENT");
    }

    @Test
    void 슬랙이_거절하면_예외() throws Exception {
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response(false, "channel_not_found"));
        assertThatThrownBy(() -> sender.send(n)).hasMessageContaining("channel_not_found");
    }

    @Test
    void 네트워크가_끊기면_예외() throws Exception {
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenThrow(new IOException("connection reset"));
        assertThatThrownBy(() -> sender.send(n)).hasMessageContaining("connection reset");
    }
}
