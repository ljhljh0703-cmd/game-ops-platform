// 「📝 패치노트」 슬랙 카드 내용 — 초안이면 [디스코드에 공지]·[고치기] 버튼, 공지했으면 누가·언제·어디
package com.ljh.gameops.notify;

import java.time.OffsetDateTime;

public record PatchCard(long id, String gameName, String title, String text, String status, boolean byAi, String actor,
                        OffsetDateTime createdAt, String editedBy, String publishedBy, OffsetDateTime publishedAt, String channelLabel,
                        int fixedCount) {
    public boolean draft() { return "DRAFT".equals(status); }
}
