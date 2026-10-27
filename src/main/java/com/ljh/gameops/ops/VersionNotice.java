// 자동 업데이트(결정 46) 알림 — 서버가 켜질 때 버전(깃 커밋)이 지난번과 다르면 슬랙 긴급 채널에 「새 버전으로 바뀌었어요」 한 줄.
// 버전은 업데이터·start.sh 가 GAMEOPS_VERSION 으로 넘긴다(「짧은 커밋 번호 커밋 제목」). 없으면 조용히 넘어간다.
// 업데이터가 되돌렸을 때 등 서버 밖에서 알릴 일은 POST /api/admin/ops-alert 로 받는다.
package com.ljh.gameops.ops;

import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "운영", description = "서버 버전 알림")
public class VersionNotice {
    private static final Logger log = LoggerFactory.getLogger(VersionNotice.class);

    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final AdminGuard guard;
    private final String version;

    public VersionNotice(JdbcTemplate jdbc, OutboxService outbox, AdminGuard guard,
                         @Value("${gameops.version:}") String version) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.guard = guard;
        this.version = version == null ? "" : version.strip();
    }

    public String version() { return version; }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        if (version.isEmpty()) return;
        List<String> prev = jdbc.queryForList("SELECT value FROM app_meta WHERE key = 'version'", String.class);
        if (!prev.isEmpty() && prev.get(0).equals(version)) return;
        jdbc.update("INSERT INTO app_meta (key, value) VALUES ('version', ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", version);
        if (prev.isEmpty()) return; // 처음 기록할 때는 알리지 않는다
        log.info("새 버전: {} (이전 {})", version, prev.get(0));
        alert("🔄 운영봇이 새 버전으로 바뀌었어요: " + version, "version:" + version);
    }

    public record AlertRequest(String text) {}

    @PostMapping("/api/admin/ops-alert")
    @Operation(summary = "운영 알림 한 줄", description = "서버 밖(자동 업데이트 등)에서 슬랙 긴급 채널로 한 줄 알린다. 500자까지")
    @SecurityRequirement(name = "adminKey")
    public Map<String, Object> post(@RequestHeader(value = "X-Api-Key", required = false) String apiKey, @RequestBody AlertRequest req) {
        guard.requireAdmin(apiKey);
        String text = req == null || req.text() == null ? "" : req.text().strip();
        if (text.isEmpty()) throw new IllegalArgumentException("text 가 비어 있어요");
        if (text.length() > 500) text = text.substring(0, 500);
        alert(text, null);
        return Map.of("ok", true);
    }

    private void alert(String text, String key) {
        if (key == null) outbox.enqueue(NotificationType.OPS_ALERT, Map.of("text", text));
        else outbox.enqueueOnce(NotificationType.OPS_ALERT, Map.of("text", text), key);
    }
}
