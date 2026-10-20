// 「이슈가 바뀌었다」 — 상태·분류·떼어내기·제보 추가. 어디서 바꿨든(슬랙·API·AI 에이전트) 이 하나로 알린다.
// 슬랙은 이걸 받아 원래 카드를 그 자리에서 바꾼다(커밋된 뒤에). what 이 null 이면 숫자만 새로(제보 수 등)
package com.ljh.gameops.notify;

/** @param channel·ts 슬랙 버튼으로 바꿨을 때 누른 카드 위치(기억해 둔 위치와 다를 수 있음). 없으면 null */
public record IssueChanged(long issueId, String actor, String what, String channel, String ts) {
    public static IssueChanged counts(long issueId) { return new IssueChanged(issueId, null, null, null, null); }

    public static IssueChanged by(long issueId, String actor, String what) { return new IssueChanged(issueId, actor, what, null, null); }
}
