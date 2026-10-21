// 도배 막기에 걸린 제보 — 플레이어에게 그대로 보여 줄 말을 담는다
package com.ljh.gameops.intake;

public class RateLimitedException extends IllegalArgumentException {
    public RateLimitedException(String message) { super(message); }
}
