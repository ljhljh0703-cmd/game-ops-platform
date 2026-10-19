// 슬랙이 꺼져 있을 때 쓰는 기본 전송기 — 로그로만 남긴다
package com.ljh.gameops.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingSender.class);

    @Override
    public void send(RenderedNotification n) {
        log.info("[알림:{}] {}", n.type(), n.text());
    }
}
