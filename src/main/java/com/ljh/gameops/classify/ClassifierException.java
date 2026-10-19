// 분류기가 답을 못 냈을 때
package com.ljh.gameops.classify;

public class ClassifierException extends RuntimeException {
    public ClassifierException(String message, Throwable cause) { super(message, cause); }
    public ClassifierException(String message) { super(message); }
}
