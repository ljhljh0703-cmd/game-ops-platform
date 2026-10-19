// 알림을 실제로 내보내는 쪽(슬랙 등) — 실패하면 예외를 던진다
package com.ljh.gameops.notify;

public interface NotificationSender {
    void send(RenderedNotification notification);
}
