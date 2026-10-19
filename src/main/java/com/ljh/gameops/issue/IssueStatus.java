// 이슈 처리 상태
package com.ljh.gameops.issue;

public enum IssueStatus {
    OPEN, IN_PROGRESS, RESOLVED, WONT_FIX;

    public boolean isOpen() { return this == OPEN || this == IN_PROGRESS; }
}
