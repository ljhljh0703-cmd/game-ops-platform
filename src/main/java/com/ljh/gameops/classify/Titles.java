// 제보 본문 첫 줄로 이슈 제목을 만든다
package com.ljh.gameops.classify;

public final class Titles {
    static final int MAX = 60;
    private Titles() {}

    public static String fromBody(String body) {
        String first = body.strip().lines().findFirst().orElse("").strip();
        if (first.isEmpty()) first = "제목 없는 제보";
        return first.length() <= MAX ? first : first.substring(0, MAX - 1) + "…";
    }
}
