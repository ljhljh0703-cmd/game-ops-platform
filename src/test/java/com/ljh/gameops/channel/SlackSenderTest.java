// 슬랙 전송 — 슬랙이 ok=false 를 주거나 네트워크가 끊기면 예외로 대기열 재시도에 넘긴다
package com.ljh.gameops.channel;

import com.ljh.gameops.channel.slack.SlackMessageBuilder;
import com.ljh.gameops.channel.slack.SlackProperties;
import com.ljh.gameops.channel.slack.SlackSender;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.RenderedNotification;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SlackSenderTest {
    private final MethodsClient client = mock(MethodsClient.class);
    private final SlackSender sender = new SlackSender(client, new SlackMessageBuilder(
            new SlackProperties(true, "x", "s", new SlackProperties.Channels("C_DEFAULT", "C_URGENT"))));
    private final RenderedNotification n = new RenderedNotification(NotificationType.NEW_ISSUE, null, Severity.HIGH, 3L, "새 이슈 #3");

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

    private final SlackSender perGame = new SlackSender(client, new SlackMessageBuilder(
            new SlackProperties(true, "x", "s", new SlackProperties.Channels("C_DEFAULT", "C_URGENT")),
            new GameCatalog(List.of(new GameSpec("movefu", "무브퓨", List.of(), "#무브퓨-알림", null)))));
    private final RenderedNotification movefuLow = new RenderedNotification(NotificationType.NEW_ISSUE, "movefu", Severity.LOW, 5L, "새 이슈 #5");

    @Test
    void 게임_채널이_없으면_공용_채널로_한_번_돌려_보낸다() throws Exception {
        when(client.chatPostMessage(any(ChatPostMessageRequest.class)))
                .thenReturn(response(false, "channel_not_found"), response(true, null));
        perGame.send(movefuLow);
        ArgumentCaptor<ChatPostMessageRequest> cap = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(client, times(2)).chatPostMessage(cap.capture());
        assertThat(cap.getAllValues()).extracting(ChatPostMessageRequest::getChannel).containsExactly("#무브퓨-알림", "C_DEFAULT");
        assertThat(cap.getAllValues().get(1).getText()).contains("#무브퓨-알림 로 못 보내");
    }

    @Test
    void 다른_오류는_돌려_보내지_않고_재시도에_맡긴다() throws Exception {
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response(false, "ratelimited"));
        assertThatThrownBy(() -> perGame.send(movefuLow)).hasMessageContaining("ratelimited");
        verify(client, times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
    }

    @Test
    void 이슈_카드는_색_띠_첨부로_보낸다() throws Exception {
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response(true, null));
        var card = new com.ljh.gameops.notify.IssueCard(3, "보석타워", "새 이슈", "튕겨요", "튕겨요", com.ljh.gameops.issue.Category.CRASH,
                Severity.HIGH, com.ljh.gameops.issue.IssueStatus.OPEN, 1, null, null, null, java.time.OffsetDateTime.now(), null);
        sender.send(new RenderedNotification(NotificationType.NEW_ISSUE, null, Severity.HIGH, 3L, "새 이슈 #3", card));
        ArgumentCaptor<ChatPostMessageRequest> cap = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(client).chatPostMessage(cap.capture());
        assertThat(cap.getValue().getAttachments()).singleElement().satisfies(a -> assertThat(a.getColor()).isEqualTo("#E8912D"));
        assertThat(cap.getValue().getText()).isEqualTo("새 이슈 #3"); // 알림 미리보기용 한 줄
    }
}
