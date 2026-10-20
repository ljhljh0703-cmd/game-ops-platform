// API 로 반영한 분류 규칙 한 판 — 시험 결과·바뀐 내용·누가·왜를 함께 남긴다(V7)
package com.ljh.gameops.classify;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "classifier_rule_version")
public class RuleVersion {
    public enum Status { ACTIVE, REVERTED, SUPERSEDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String rulesJson;
    private String baseHash;
    @Enumerated(EnumType.STRING) private Status status;
    private String actor;
    private String reason;
    private String summary;
    private double devBefore;
    private double devAfter;
    private int regressionTotal;
    private OffsetDateTime createdAt;
    private OffsetDateTime revertedAt;
    private String revertedBy;

    protected RuleVersion() {}

    public RuleVersion(String rulesJson, String baseHash, String actor, String reason, String summary,
                       double devBefore, double devAfter, int regressionTotal) {
        this.rulesJson = rulesJson;
        this.baseHash = baseHash;
        this.status = Status.ACTIVE;
        this.actor = actor;
        this.reason = reason;
        this.summary = summary;
        this.devBefore = devBefore;
        this.devAfter = devAfter;
        this.regressionTotal = regressionTotal;
        this.createdAt = OffsetDateTime.now();
    }

    public void supersede() { this.status = Status.SUPERSEDED; }

    public void revert(String by) {
        this.status = Status.REVERTED;
        this.revertedBy = by;
        this.revertedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getRulesJson() { return rulesJson; }
    public String getBaseHash() { return baseHash; }
    public Status getStatus() { return status; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public String getSummary() { return summary; }
    public double getDevBefore() { return devBefore; }
    public double getDevAfter() { return devAfter; }
    public int getRegressionTotal() { return regressionTotal; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getRevertedAt() { return revertedAt; }
    public String getRevertedBy() { return revertedBy; }
}
