// 「🧠 최근 피드백 정리」 카드 내용 — AI 가 쓴 정리 글과 범위(게임·기간·제보 수)
package com.ljh.gameops.notify;

import java.time.OffsetDateTime;

public record DigestCard(long digestId, String gameName, int days, int reportCount, String text, String actor, OffsetDateTime createdAt) {}
