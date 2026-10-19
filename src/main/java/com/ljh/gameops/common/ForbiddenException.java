// 권한이 없을 때 403으로 돌려줄 예외
package com.ljh.gameops.common;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
