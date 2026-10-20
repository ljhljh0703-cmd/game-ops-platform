// 이슈 알림 카드에 들어갈 내용 — 채널(슬랙 등)과 상관없는 값만 담는다. 모양은 각 채널이 정한다
package com.ljh.gameops.notify;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.report.SourceChannel;

import java.time.OffsetDateTime;

/**
 * @param label      알림 종류 문구(「새 이슈」「심각도 상승(보통→)」 등)
 * @param quote      대표 제보 원문. 플레이어가 쓴 글이라 믿지 않는 데이터다 — 채널이 표시할 때 반드시 이스케이프한다
 * @param lastAction 방금 사람이 한 일(「✅ 해결됨 · 누가 · 언제」). 처음 알림이면 null
 */
public record IssueCard(long issueId, String gameName, String label, String title, String quote,
                        Category category, Severity severity, IssueStatus status, int reportCount,
                        Long recurrenceOf, Long detachedFrom, SourceChannel source, OffsetDateTime createdAt,
                        String lastAction) {

    public IssueCard withLastAction(String action) {
        return new IssueCard(issueId, gameName, label, title, quote, category, severity, status, reportCount,
                recurrenceOf, detachedFrom, source, createdAt, action);
    }
}
