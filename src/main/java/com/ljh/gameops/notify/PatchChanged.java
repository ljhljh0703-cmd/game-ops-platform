// 패치노트가 바뀌었다(고침·공지) — 슬랙 카드를 그 자리에서 다시 그린다. channel·ts 는 버튼을 누른 카드 위치(없으면 null)
package com.ljh.gameops.notify;

public record PatchChanged(long patchId, String channel, String ts) {}
