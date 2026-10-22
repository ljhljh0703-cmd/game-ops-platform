// 시험용 — IssueCardFactory 의 패키지 안 함수를 다른 패키지 시험에서 부른다
package com.ljh.gameops.notify;

public final class IssueCardFactoryAccess {
    private IssueCardFactoryAccess() {}

    public static String quote(com.ljh.gameops.report.Report r) { return IssueCardFactory.quoteOf(r); }
}
