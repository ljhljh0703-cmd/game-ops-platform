// 슬랙 버튼 주소 지킴이 — 주소가 바뀔 때만 한 번 알리고, 터널이 꺼져 있거나 이상한 답이면 조용히 넘긴다
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TunnelUrlWatcherTest {
    @Test
    void 주소가_바뀔_때만_알린다() {
        OutboxService outbox = mock(OutboxService.class);
        when(outbox.enqueueOnce(any(), any(), any())).thenReturn(true);
        Deque<Object> answers = new ArrayDeque<>();
        answers.add("calm-river-blue-sky.trycloudflare.com");
        answers.add("calm-river-blue-sky.trycloudflare.com"); // 같은 주소: 다시 알리지 않음
        answers.add(new IllegalStateException("터널 꺼짐"));     // 터널 꺼짐: 조용히 넘김
        answers.add("api.trycloudflare.com");                  // 실패 기록용 주소: 거름
        answers.add("");                                      // 아직 주소 없음
        answers.add("new-moon-red-fox.trycloudflare.com");     // 바뀐 주소: 알림
        var w = new TunnelUrlWatcher(() -> {
            Object a = answers.pop();
            if (a instanceof RuntimeException e) throw e;
            return (String) a;
        }, outbox);
        for (int i = 0; i < 6; i++) w.check();

        verify(outbox).enqueueOnce(eq(NotificationType.SLACK_URL_CHANGED),
                argThat((Map<String, Object> p) -> p.get("url").equals("https://calm-river-blue-sky.trycloudflare.com/slack/interactions")),
                eq("slack-url:calm-river-blue-sky.trycloudflare.com"));
        verify(outbox).enqueueOnce(eq(NotificationType.SLACK_URL_CHANGED),
                argThat((Map<String, Object> p) -> p.get("text").toString().contains("Request URL")),
                eq("slack-url:new-moon-red-fox.trycloudflare.com"));
        verifyNoMoreInteractions(outbox);
    }
}
