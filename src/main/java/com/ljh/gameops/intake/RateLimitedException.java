// 도배·몰림 막기에 걸린 제보 — 플레이어에게 보여 줄 말과 이유(결정 36 · 43)
package com.ljh.gameops.intake;

public class RateLimitedException extends IllegalArgumentException {
    private final IntakeGuard.Reason reason;

    public RateLimitedException(String message) { this(IntakeGuard.Reason.AUTHOR, message); }

    public RateLimitedException(IntakeGuard.Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public IntakeGuard.Reason reason() { return reason; }
}
