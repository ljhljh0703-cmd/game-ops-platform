// 이슈 처리 상태
package com.ljh.gameops.issue;

public enum IssueStatus {
    /**
     * VERIFYING = 코드는 고쳐 합쳐졌고 QA 확인을 기다림(결정 31). 게임 설정 qa=true 일 때 PR 합치기가 여기로 보낸다.
     * QA가 확인하면 RESOLVED(그때 플레이어에게 「고쳤어요」), 아니면 OPEN 으로 되돌린다. 새 제보를 붙이는 「열린」 상태는 아니다
     */
    OPEN, IN_PROGRESS, RESOLVED, WONT_FIX, VERIFYING;

    public boolean isOpen() { return this == OPEN || this == IN_PROGRESS; }
}
