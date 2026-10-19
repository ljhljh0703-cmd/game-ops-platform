// 제보 본문을 비교용으로 정규화하고, 같은 글을 가리키는 키를 만든다
package com.ljh.gameops.issue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TextNormalizer {
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern REPEATED_JAMO = Pattern.compile("[ㄱ-ㅎㅏ-ㅣ]{2,}"); // ㅋㅋ, ㅠㅠ 같은 감탄
    private static final Pattern NON_TEXT = Pattern.compile("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}\\s]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TextNormalizer() {}

    public static String normalize(String body) {
        String s = body.toLowerCase(Locale.ROOT);
        s = URL.matcher(s).replaceAll(" ");
        s = REPEATED_JAMO.matcher(s).replaceAll(" ");
        s = NON_TEXT.matcher(s).replaceAll(" ");
        return SPACES.matcher(s).replaceAll(" ").strip();
    }

    /** 정규화된 본문이 완전히 같으면 같은 키. 동시 제보 경합을 DB 고유 인덱스로 막는 데 쓴다. */
    public static String key(String normalized) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
