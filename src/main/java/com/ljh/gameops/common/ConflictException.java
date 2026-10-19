// 지금 상태에서는 할 수 없는 요청 — 409로 돌려준다
package com.ljh.gameops.common;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
