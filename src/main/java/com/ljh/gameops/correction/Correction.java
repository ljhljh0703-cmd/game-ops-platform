// 사람이 자동 판단을 고친 한 건
package com.ljh.gameops.correction;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "correction")
public class Correction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gameId;
    @Enumerated(EnumType.STRING) private CorrectionKind kind;
    private Long issueId;
    private Long reportId;
    private Long newIssueId;
    private String beforeValue;
    private String afterValue;
    private Double similarity;
    private String autoBy;
    private String actor;
    private String reason;
    private OffsetDateTime createdAt;

    protected Correction() {}

    public Correction(Long gameId, CorrectionKind kind, Long issueId, Long reportId, Long newIssueId,
                      String beforeValue, String afterValue, Double similarity, String autoBy,
                      String actor, String reason) {
        this.gameId = gameId;
        this.kind = kind;
        this.issueId = issueId;
        this.reportId = reportId;
        this.newIssueId = newIssueId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.similarity = similarity;
        this.autoBy = autoBy;
        this.actor = actor;
        this.reason = reason;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public CorrectionKind getKind() { return kind; }
    public Long getIssueId() { return issueId; }
    public Long getReportId() { return reportId; }
    public Long getNewIssueId() { return newIssueId; }
    public String getBeforeValue() { return beforeValue; }
    public String getAfterValue() { return afterValue; }
    public Double getSimilarity() { return similarity; }
    public String getAutoBy() { return autoBy; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
