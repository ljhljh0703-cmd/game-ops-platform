// 슬랙 버튼 눌림 받기 — 서명 확인 → 관리자 확인 → 이슈 상태 변경(감사 기록 포함)
package com.ljh.gameops.channel.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/slack")
public class SlackInteractionController {
    private static final Logger log = LoggerFactory.getLogger(SlackInteractionController.class);

    private final SlackProperties props;
    private final AdminGuard guard;
    private final IssueService issues;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SlackInteractionController(SlackProperties props, AdminGuard guard, IssueService issues,
                                      ObjectMapper mapper, Clock clock) {
        this.props = props;
        this.guard = guard;
        this.issues = issues;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping(value = "/interactions", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, String>> interact(@RequestBody String rawBody,
                                                        @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String ts,
                                                        @RequestHeader(value = "X-Slack-Signature", required = false) String sig) {
        if (props.signingSecret() == null || props.signingSecret().isBlank()
                || !new SlackSignatureVerifier(props.signingSecret()).verify(ts, rawBody, sig, clock.instant())) {
            return ResponseEntity.status(401).body(Map.of("error", "서명 불일치"));
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(formField(rawBody, "payload"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "payload 해석 실패"));
        }
        String userId = payload.path("user").path("id").asText(null);
        String value = payload.path("actions").path(0).path("value").asText("");
        if (!guard.isSlackAdmin(userId)) {
            log.info("슬랙 비관리자 버튼 무시 user={}", userId);
            return ResponseEntity.ok(Map.of("text", "상태 변경 권한이 없어요."));
        }
        String[] parts = value.split(":");
        if (parts.length != 2) return ResponseEntity.badRequest().body(Map.of("error", "버튼 값 형식 오류"));
        long issueId = Long.parseLong(parts[0]);
        IssueStatus next = IssueStatus.valueOf(parts[1]);
        issues.changeStatus(issueId, next, "slack:" + userId, "slack");
        return ResponseEntity.ok(Map.of("text", "이슈 #" + issueId + " → " + next));
    }

    static String formField(String body, String name) {
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("필드 없음: " + name);
    }
}
