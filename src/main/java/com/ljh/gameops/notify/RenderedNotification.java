// 보낼 준비가 된 알림 — 게임과 심각도로 채널을 고르고, 이슈면 카드(card)로 그린다. text 는 카드를 못 그리는 곳(알림 미리보기·로그)용 한 줄
package com.ljh.gameops.notify;

import com.ljh.gameops.issue.Severity;

public record RenderedNotification(NotificationType type, String gameCode, Severity severity, Long issueId, String text, IssueCard card) {
    public RenderedNotification(NotificationType type, String gameCode, Severity severity, Long issueId, String text) {
        this(type, gameCode, severity, issueId, text, null);
    }

    public RenderedNotification withText(String newText) {
        return new RenderedNotification(type, gameCode, severity, issueId, newText, card);
    }
}
