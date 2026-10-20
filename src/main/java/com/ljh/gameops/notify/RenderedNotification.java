// 보낼 준비가 된 알림 — 게임과 심각도로 채널을 고르고, 이슈면 상태 버튼을 붙인다
package com.ljh.gameops.notify;

import com.ljh.gameops.issue.Severity;

public record RenderedNotification(NotificationType type, String gameCode, Severity severity, Long issueId, String text) {}
