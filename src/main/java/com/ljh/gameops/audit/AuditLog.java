// 누가 무엇을 바꿨는지 남기는 감사 기록
package com.ljh.gameops.audit;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String actor;
    private String action;
    private String target;
    private String detail;
    private OffsetDateTime createdAt;

    protected AuditLog() {}

    public AuditLog(String actor, String action, String target, String detail) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.createdAt = OffsetDateTime.now();
    }

    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getDetail() { return detail; }
}
