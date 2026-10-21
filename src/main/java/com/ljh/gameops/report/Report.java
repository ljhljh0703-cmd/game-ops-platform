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
    /** 답장할 곳(디스코드: 「채널 ID:메시지 ID」). 없으면 답장하지 않는다(V6) */
    private String replyTo;
    /** 재현 정보(V12). 입력칸 값이 먼저, 없으면 글에서 찾은 값 */
    private String buildVersion;
    private String device;
    private String reproSteps;
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

    public Report(Long gameId, SourceChannel sourceChannel, String externalId, String authorHash,
                  String body, String attachmentUrls, String replyTo) {
        this(gameId, sourceChannel, externalId, authorHash, body, attachmentUrls);
        this.replyTo = replyTo;
    }

    public String getReplyTo() { return replyTo; }

    public Report withRepro(ReproInfo r) {
        if (r != null) {
            this.buildVersion = r.version();
            this.device = r.device();
            this.reproSteps = r.steps();
        }
        return this;
    }

    /** 플레이어가 답글로 더 알려 준 재현 정보·스크린샷을 빈 칸에만 채운다(먼저 적힌 값은 그대로) */
    public void addRepro(ReproInfo more, String moreAttachments) {
        ReproInfo merged = ReproInfo.merge(repro(), more);
        withRepro(merged);
        if (moreAttachments != null && !moreAttachments.isBlank()) {
            this.attachmentUrls = attachmentUrls == null || attachmentUrls.isBlank() ? moreAttachments : attachmentUrls + "\n" + moreAttachments;
        }
    }

    public ReproInfo repro() { return new ReproInfo(buildVersion, device, reproSteps); }
    public String getBuildVersion() { return buildVersion; }
    public String getDevice() { return device; }
    public String getReproSteps() { return reproSteps; }

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public SourceChannel getSourceChannel() { return sourceChannel; }
    public String getExternalId() { return externalId; }
    public String getAuthorHash() { return authorHash; }
    public String getBody() { return body; }
    public String getAttachmentUrls() { return attachmentUrls; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
