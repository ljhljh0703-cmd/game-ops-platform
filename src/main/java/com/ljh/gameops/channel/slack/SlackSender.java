// 슬랙으로 알림 보내기 — 응답이 ok 가 아니면 예외를 던져 대기열이 다시 보내게 한다.
// 게임 채널이 없거나 봇이 못 들어가는 경우(다시 보내도 안 되는 오류)는 공용 채널로 한 번 돌려 보낸다.
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.notify.NotificationSender;
import com.ljh.gameops.notify.RenderedNotification;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class SlackSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(SlackSender.class);
    private static final Set<String> CHANNEL_ERRORS = Set.of("channel_not_found", "not_in_channel", "is_archived");
    private final MethodsClient client;
    private final SlackMessageBuilder builder;

    public SlackSender(MethodsClient client, SlackMessageBuilder builder) {
        this.client = client;
        this.builder = builder;
    }

    @Override
    public void send(RenderedNotification n) {
        String channel = builder.channelFor(n);
        ChatPostMessageResponse r = post(channel, n, n.text());
        if (r != null && !r.isOk() && CHANNEL_ERRORS.contains(r.getError())) {
            String shared = builder.sharedFallbackFor(n, channel);
            if (shared != null) {
                log.warn("슬랙 채널 {} 로 못 보내 공용 채널로 보냅니다: {} (games.json 채널 이름·봇 초대 확인)", channel, r.getError());
                r = post(shared, n, n.text() + "\n_(게임 채널 " + channel + " 로 못 보내 여기로 왔어요: " + r.getError() + ")_");
            }
        }
        if (r == null || !r.isOk()) {
            throw new IllegalStateException("슬랙 응답 오류: " + (r == null ? "null" : r.getError()));
        }
    }

    private ChatPostMessageResponse post(String channel, RenderedNotification n, String text) {
        try {
            return client.chatPostMessage(ChatPostMessageRequest.builder()
                    .channel(channel)
                    .text(text)
                    .blocks(builder.blocks(n.withText(text)))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("슬랙 호출 실패: " + e.getMessage(), e);
        }
    }
}
