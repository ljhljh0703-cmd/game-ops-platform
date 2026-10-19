// 플레이어 원본 제보 엔티티 — 작성자는 해시로만 저장한다
package com.ljh.gameops.report;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "report")
public class Report {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gameId;
    @Enumerated(EnumType.STRING)
    private SourceChannel sourceChannel;
    private String externalId;
    private String authorHash;
    private String body;
    private String attachmentUrls;
    private OffsetDateTime receivedAt;

    protected Report() {}

    public Report(Long gameId, SourceChannel sourceChannel, String externalId, String authorHash,
                  String body, String attachmentUrls) {
        this.gameId = gameId;
        this.sourceChannel = sourceChannel;
        this.externalId = externalId;
        this.authorHash = authorHash;
        this.body = body;
        this.attachmentUrls = attachmentUrls;
        this.receivedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public SourceChannel getSourceChannel() { return sourceChannel; }
    public String getExternalId() { return externalId; }
    public String getAuthorHash() { return authorHash; }
    public String getBody() { return body; }
    public String getAttachmentUrls() { return attachmentUrls; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
