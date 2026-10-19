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

    /** 사람이 분류·심각도를 고칠 때. 사람은 심각도를 내릴 수도 있다. */
    public void reclassify(Category category, Severity severity) {
        this.category = category;
        this.severity = severity;
        this.classifiedBy = "human";
        this.updatedAt = OffsetDateTime.now();
    }

    /** 잘못 묶인 제보 하나를 떼어낼 때. 심각도는 자동으로 내리지 않는다(내릴지는 사람이 판단). */
    public void detachReport() {
        if (this.reportCount <= 1) throw new com.ljh.gameops.common.ConflictException("제보가 하나뿐인 이슈에서는 떼어낼 수 없습니다");
        this.reportCount--;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 대표 제보가 떼어졌을 때 남은 제보로 대표를 바꾼다. 이후 중복 비교도 새 대표 글로 한다. */
    public void replaceRepresentative(Long reportId, String title, String normalizedKey, String normalizedText) {
        this.representativeReportId = reportId;
        this.title = title;
        this.normalizedKey = normalizedKey;
        this.normalizedText = normalizedText;
        this.updatedAt = OffsetDateTime.now();
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
