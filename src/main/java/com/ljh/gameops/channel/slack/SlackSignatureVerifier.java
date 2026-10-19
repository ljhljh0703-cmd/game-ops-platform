// 슬랙 요청 서명 검사 — 「v0:시각:본문」 HMAC-SHA256, 5분 지난 요청은 재전송 공격으로 보고 거절
package com.ljh.gameops.channel.slack;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

public class SlackSignatureVerifier {
    static final Duration MAX_AGE = Duration.ofMinutes(5);
    private final byte[] secret;

    public SlackSignatureVerifier(String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) throw new IllegalArgumentException("signing secret 이 비어 있습니다");
        this.secret = signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(String timestamp, String body, String signature, Instant now) {
        if (timestamp == null || signature == null || body == null) return false;
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(ts), now).abs().compareTo(MAX_AGE) > 0) return false;
        String expected = sign(timestamp, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] d = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
