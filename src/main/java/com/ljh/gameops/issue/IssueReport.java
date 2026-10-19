// 이슈와 제보의 연결 — 제보 하나는 이슈 하나에만 붙는다
package com.ljh.gameops.issue;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "issue_report")
@IdClass(IssueReport.Key.class)
public class IssueReport {
    @Id private Long issueId;
    @Id private Long reportId;
    private double similarity;
    private OffsetDateTime linkedAt;

    protected IssueReport() {}

    public IssueReport(Long issueId, Long reportId, double similarity) {
        this.issueId = issueId;
        this.reportId = reportId;
        this.similarity = similarity;
        this.linkedAt = OffsetDateTime.now();
    }

    public Long getIssueId() { return issueId; }
    public Long getReportId() { return reportId; }
    public double getSimilarity() { return similarity; }
    public OffsetDateTime getLinkedAt() { return linkedAt; }

    public static class Key implements Serializable {
        private Long issueId;
        private Long reportId;
        public Key() {}
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(issueId, k.issueId) && Objects.equals(reportId, k.reportId);
        }
        @Override public int hashCode() { return Objects.hash(issueId, reportId); }
    }
}
