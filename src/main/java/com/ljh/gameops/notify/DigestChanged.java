// 피드백 정리 카드가 바뀜(개발 AI에게 넘김) — 커밋 뒤 슬랙 카드를 그 자리에서 다시 그린다. channel/ts 는 버튼을 누른 카드(기억용)
package com.ljh.gameops.notify;

public record DigestChanged(long digestId, String channel, String ts) {}
