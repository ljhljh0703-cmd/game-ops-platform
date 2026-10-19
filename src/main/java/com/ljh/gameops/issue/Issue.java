// 묶인 제보들의 대표 이슈 엔티티 — 낙관적 잠금(version)으로 동시 수정을 막는다
package com.ljh.gameops.issue;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "issue")
public class Issue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gameId;
    private String title;
    @Enumerated(EnumType.STRING) private Category category;
    @Enumerated(EnumType.STRING) private Severity severity;
    @Enumerated(EnumType.STRING) private IssueStatus status;
    private String normalizedKey;
    private String normalizedText;
    private Long representativeReportId;
    private int reportCount;
    private String classifiedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime resolvedAt;
    @Version private long version;

    protected Issue() {}

    public Issue(Long gameId, String title, Category category, Severity severity,
                 String normalizedKey, String normalizedText, Long representativeReportId, String classifiedBy) {
        this.gameId = gameId;
        this.title = title;
        this.category = category;
        this.severity = severity;
        this.status = IssueStatus.OPEN;
        this.normalizedKey = normalizedKey;
        this.normalizedText = normalizedText;
        this.representativeReportId = representativeReportId;
        this.reportCount = 1;
        this.classifiedBy = classifiedBy;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** 중복 제보가 붙을 때. 더 심각한 제보가 오면 심각도를 올린다. */
    public void attachReport(Severity incoming) {
        this.reportCount++;
        if (incoming.ordinal() > this.severity.ordinal()) this.severity = incoming;
        this.updatedAt = OffsetDateTime.now();
    }

    public void changeStatus(IssueStatus next) {
        this.status = next;
        this.updatedAt = OffsetDateTime.now();
        this.resolvedAt = (next == IssueStatus.RESOLVED || next == IssueStatus.WONT_FIX) ? this.updatedAt : null;
    }

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public String getTitle() { return title; }
    public Category getCategory() { return category; }
    public Severity getSeverity() { return severity; }
    public IssueStatus getStatus() { return status; }
    public String getNormalizedKey() { return normalizedKey; }
    public String getNormalizedText() { return normalizedText; }
    public Long getRepresentativeReportId() { return representativeReportId; }
    public int getReportCount() { return reportCount; }
    public String getClassifiedBy() { return classifiedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public long getVersion() { return version; }
}
