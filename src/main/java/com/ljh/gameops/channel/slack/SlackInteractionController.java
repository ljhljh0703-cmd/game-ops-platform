// 슬랙 버튼 눌림 받기 — 서명 확인 → 관리자 확인 → 이슈 상태 변경(감사 기록 포함)
package com.ljh.gameops.channel.slack;

import io.swagger.v3.oas.annotations.Hidden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/slack")
@Hidden // 슬랙만 부르는 주소라 API 안내에서 뺀다
public class SlackInteractionController {
    private static final Logger log = LoggerFactory.getLogger(SlackInteractionController.class);

    private final SlackProperties props;
    private final AdminGuard guard;
    private final IssueService issues;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SlackResponder responder;

    public SlackInteractionController(SlackProperties props, AdminGuard guard, IssueService issues,
                                      ObjectMapper mapper, Clock clock, SlackResponder responder) {
        this.props = props;
        this.guard = guard;
        this.issues = issues;
        this.mapper = mapper;
        this.clock = clock;
        this.responder = responder;
    }

    @PostMapping(value = "/interactions", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, String>> interact(HttpServletRequest request,
                                                        @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String ts,
                                                        @RequestHeader(value = "X-Slack-Signature", required = false) String sig) {
        // 서명은 슬랙이 보낸 원래 바이트 그대로 계산해야 한다. @RequestBody 로 받으면 스프링이 폼을 다시 조립해
        // 인코딩(%20 대 +, 대소문자)이 달라지고 서명이 어긋난다.
        String rawBody;
        try {
            rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "본문 읽기 실패"));
        }
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
        String responseUrl = payload.path("response_url").asText(null);
        if (!guard.isSlackAdmin(userId)) {
            log.info("슬랙 비관리자 버튼 무시 user={}", userId);
            responder.reply(responseUrl, "상태 변경 권한이 없어요.");
            return ResponseEntity.ok(Map.of("text", "상태 변경 권한이 없어요."));
        }
        String[] parts = value.split(":");
        if (parts.length != 2) return ResponseEntity.badRequest().body(Map.of("error", "버튼 값 형식 오류"));
        long issueId = Long.parseLong(parts[0]);
        IssueStatus next = IssueStatus.valueOf(parts[1]);
        issues.changeStatus(issueId, next, "slack:" + userId, "slack");
        String done = "이슈 #" + issueId + " → " + next;
        responder.reply(responseUrl, done);
        return ResponseEntity.ok(Map.of("text", done));
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
