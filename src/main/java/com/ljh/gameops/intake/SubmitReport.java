// 제보 접수 명령 — 디스코드·슬랙·REST 어디서 오든 이 모양으로 들어온다
package com.ljh.gameops.intake;

import com.ljh.gameops.report.SourceChannel;

public record SubmitReport(String gameCode, SourceChannel channel, String externalId,
                           String authorId, String body, String attachmentUrls) {
    public SubmitReport {
        if (gameCode == null || gameCode.isBlank()) throw new IllegalArgumentException("게임 코드가 비어 있습니다");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("제보 내용이 비어 있습니다");
        if (authorId == null || authorId.isBlank()) throw new IllegalArgumentException("작성자 정보가 비어 있습니다");
        if (externalId != null && externalId.length() > 100) throw new IllegalArgumentException("외부 ID는 100자까지 받습니다");
        if (body.length() > 4000) throw new IllegalArgumentException("제보는 4000자까지 받습니다");
    }
}
