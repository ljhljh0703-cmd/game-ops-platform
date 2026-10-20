// 「반영한 규칙 판이 바뀌었다(되돌림 등)」 — 슬랙 규칙 카드를 그 자리에서 바꾸게 한다
package com.ljh.gameops.notify;

public record RulesChanged(long versionId, String actor, String what, String channel, String ts) {}
