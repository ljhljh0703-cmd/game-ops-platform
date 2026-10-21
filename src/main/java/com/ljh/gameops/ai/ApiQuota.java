// 외부 API 남은 한도 — 깃허브는 응답 머리글(X-RateLimit-*)로 시간당 남은 요청 수를 알려 준다. 마지막으로 본 값을 들고 있다
package com.ljh.gameops.ai;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ApiQuota {
    /** @param resetAt 한도가 다시 차는 때 */
    public record GitHub(int limit, int remaining, Instant resetAt, Instant seenAt) {
        public String line() {
            return "깃허브 요청 " + remaining + "/" + limit + " 남음(시간당)";
        }
    }

    private final AtomicReference<GitHub> github = new AtomicReference<>();

    public void updateGitHub(HttpHeaders h) {
        String limit = h.getFirst("X-RateLimit-Limit"), remaining = h.getFirst("X-RateLimit-Remaining"), reset = h.getFirst("X-RateLimit-Reset");
        if (limit == null || remaining == null) return;
        try {
            github.set(new GitHub(Integer.parseInt(limit), Integer.parseInt(remaining),
                    reset == null ? null : Instant.ofEpochSecond(Long.parseLong(reset)), Instant.now()));
        } catch (NumberFormatException ignore) {}
    }

    public GitHub github() { return github.get(); }
}
