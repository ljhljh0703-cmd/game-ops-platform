// 제보 접수 결과 — 새 이슈인지, 기존 이슈에 붙었는지, 같은 메시지가 다시 온 것인지
package com.ljh.gameops.intake;

public record IntakeResult(long reportId, long issueId, Outcome outcome, double similarity) {
    public enum Outcome { NEW_ISSUE, ATTACHED_TO_EXISTING, DUPLICATE_DELIVERY }
}
