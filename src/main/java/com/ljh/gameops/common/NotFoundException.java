// 대상을 찾지 못했을 때 404로 돌려줄 예외
package com.ljh.gameops.common;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
