// 관리자 확인 — API 키를 상수 시간 비교로 대조해 관리자 이름을 돌려준다
package com.ljh.gameops.common;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public class AdminGuard {
    private final AdminProperties props;

    public AdminGuard(AdminProperties props) { this.props = props; }

    /** api-keys 는 「이름 → 키」 형태. 맞는 키면 이름을 돌려준다. */
    public String requireAdmin(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            byte[] given = apiKey.getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<String, String> e : props.apiKeys().entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank()) continue; // 비어 있는 키는 아무도 통과시키지 않는다
                if (MessageDigest.isEqual(given, e.getValue().getBytes(StandardCharsets.UTF_8))) return e.getKey();
            }
        }
        throw new ForbiddenException("관리자 권한이 필요합니다");
    }

    public boolean isSlackAdmin(String slackUserId) {
        return slackUserId != null && props.slackUserIds().contains(slackUserId);
    }
}
