// 작성자 ID를 소금(salt)을 친 해시로 바꾼다 — 원본 ID는 저장하지 않는다
package com.ljh.gameops.intake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class AuthorHasher {
    private final String salt;

    public AuthorHasher(@Value("${gameops.author-hash-salt}") String salt) {
        if (salt == null || salt.length() < 8) throw new IllegalStateException("gameops.author-hash-salt 는 8자 이상이어야 합니다");
        this.salt = salt;
    }

    public String hash(String channel, String authorId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            md.update((channel + ":" + authorId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
