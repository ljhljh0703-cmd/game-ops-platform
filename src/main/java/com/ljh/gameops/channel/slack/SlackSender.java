// 슬랙으로 알림 보내기 — 응답이 ok 가 아니면 예외를 던져 대기열이 다시 보내게 한다
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.notify.NotificationSender;
import com.ljh.gameops.notify.RenderedNotification;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;

public class SlackSender implements NotificationSender {
    private final MethodsClient client;
    private final SlackMessageBuilder builder;

    public SlackSender(MethodsClient client, SlackMessageBuilder builder) {
        this.client = client;
        this.builder = builder;
    }

    @Override
    public void send(RenderedNotification n) {
        ChatPostMessageResponse r;
        try {
            r = client.chatPostMessage(ChatPostMessageRequest.builder()
                    .channel(builder.channelFor(n))
                    .text(n.text())
                    .blocks(builder.blocks(n))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("슬랙 호출 실패: " + e.getMessage(), e);
        }
        if (r == null || !r.isOk()) {
            throw new IllegalStateException("슬랙 응답 오류: " + (r == null ? "null" : r.getError()));
        }
    }
}
