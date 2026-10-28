// 피드백 정리 카드 지금 모습 — 정리 글 + 개발 AI에게 넘긴 기록. 처음 보낼 때와 넘긴 뒤 다시 그릴 때 같은 것을 쓴다
package com.ljh.gameops.notify;

import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.handoff.DevHandoffService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DigestCardFactory {
    private final FeedbackService feedback;
    private final ObjectProvider<DevHandoffService> handoff;
    private final ObjectProvider<com.ljh.gameops.jira.JiraHandoffService> jira;
    private final ObjectProvider<com.ljh.gameops.handoff.DevFixService> fixes;

    public DigestCardFactory(FeedbackService feedback, ObjectProvider<DevHandoffService> handoff,
                             ObjectProvider<com.ljh.gameops.jira.JiraHandoffService> jira,
                             ObjectProvider<com.ljh.gameops.handoff.DevFixService> fixes) {
        this.feedback = feedback;
        this.handoff = handoff;
        this.jira = jira;
        this.fixes = fixes;
    }

    public DigestCard current(long digestId) {
        var d = feedback.get(digestId);
        DevHandoffService h = handoff.getIfAvailable();
        List<DigestCard.Handoff> list = h == null ? List.of() : h.handoffs(digestId);
        var j = jira.getIfAvailable();
        var f = fixes.getIfAvailable();
        boolean runner = f != null && f.enabled();
        return new DigestCard(d.id(), d.gameName(), d.days(), d.reportCount(), d.text(), d.actor(), d.createdAt(), list,
                j == null ? List.of() : j.tickets(digestId), j != null && j.available(d.gameCode()),
                f == null ? List.of() : f.forDigest(digestId), runner);
    }
}
