// 테스트용 전송기 — 보낸 알림을 모으고, 원하면 일부러 실패한다
package com.ljh.gameops;

import com.ljh.gameops.notify.NotificationSender;
import com.ljh.gameops.notify.RenderedNotification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeSender implements NotificationSender {
    public final List<RenderedNotification> sent = new CopyOnWriteArrayList<>();
    public final AtomicInteger failNext = new AtomicInteger();

    @Override
    public void send(RenderedNotification n) {
        if (failNext.getAndUpdate(v -> Math.max(0, v - 1)) > 0) throw new IllegalStateException("slack 503 (가짜)");
        sent.add(n);
    }

    public void reset() {
        sent.clear();
        failNext.set(0);
    }
}
