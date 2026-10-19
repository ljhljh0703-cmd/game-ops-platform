// 알림 대기열 한 건 — 외부 전송이 실패해도 여기 남아 다시 보낸다
package com.ljh.gameops.notify;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "notification_outbox")
public class OutboxMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) private NotificationType type;
    private String payload;
    @Enumerated(EnumType.STRING) private OutboxStatus status;
    private int attempts;
    private OffsetDateTime nextAttemptAt;
    private String lastError;
    private OffsetDateTime createdAt;
    private OffsetDateTime sentAt;

    protected OutboxMessage() {}

    public OutboxMessage(NotificationType type, String payload) {
        this.type = type;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
        this.nextAttemptAt = this.createdAt;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = OffsetDateTime.now();
        this.lastError = null;
    }

    public void markFailedAttempt(String error, int maxAttempts, long baseBackoffMs) {
        this.attempts++;
        this.lastError = error == null ? "unknown" : (error.length() > 1000 ? error.substring(0, 1000) : error);
        if (attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        } else {
            long delay = baseBackoffMs * (1L << (attempts - 1)); // 1, 2, 4, 8배
            this.nextAttemptAt = OffsetDateTime.now().plusNanos(delay * 1_000_000);
        }
    }

    public Long getId() { return id; }
    public NotificationType getType() { return type; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
}
