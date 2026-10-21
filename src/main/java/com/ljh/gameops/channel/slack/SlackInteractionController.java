// 슬랙 버튼·입력 창 받기 — 서명 확인 → 관리자 확인 → 처리(감사·고친 기록 포함) → 원래 카드를 그 자리에서 바꾼다.
// 상태 버튼: 진행 중·해결·보류·다시 열기. 고치기 버튼: 분류 고치기·떼어내기는 입력 창을 열고, 창을 저장하면 처리한다.
package com.ljh.gameops.channel.slack;

import io.swagger.v3.oas.annotations.Hidden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.common.ConflictException;
import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.correction.CorrectionService;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueReport;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.IssueCard;
import com.ljh.gameops.notify.IssueCardFactory;
import com.ljh.gameops.report.ReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/slack")
@Hidden // 슬랙만 부르는 주소라 API 안내에서 뺀다
public class SlackInteractionController {
    private static final Logger log = LoggerFactory.getLogger(SlackInteractionController.class);
    private static final Pattern SLACK_ID = Pattern.compile("[A-Z0-9_]{2,30}");

    private final SlackProperties props;
    private final AdminGuard guard;
    private final IssueService issues;
    private final CorrectionService corrections;
    private final ReportRepository reports;
    private final IssueCardFactory cards;
    private final SlackWebApi web;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SlackResponder responder;
    private final com.ljh.gameops.classify.RuleChangeService rules;
    private final SlackMessageStore store;
    private final SlackHomeService home;
    private final com.ljh.gameops.handoff.DevHandoffService handoff;
    private final com.ljh.gameops.jira.JiraHandoffService jira;
    // 깃허브 이슈 만들기는 3초를 넘길 수 있어 뒤에서
    private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();

    public SlackInteractionController(SlackProperties props, AdminGuard guard, IssueService issues, CorrectionService corrections,
                                      ReportRepository reports, IssueCardFactory cards, SlackWebApi web,
                                      ObjectMapper mapper, Clock clock, SlackResponder responder,
                                      com.ljh.gameops.classify.RuleChangeService rules, SlackMessageStore store,
                                      SlackHomeService home, com.ljh.gameops.handoff.DevHandoffService handoff,
                                      com.ljh.gameops.jira.JiraHandoffService jira) {
        this.jira = jira;
        this.home = home;
        this.handoff = handoff;
        this.rules = rules;
        this.store = store;
        this.props = props;
        this.guard = guard;
        this.issues = issues;
        this.corrections = corrections;
        this.reports = reports;
        this.cards = cards;
        this.web = web;
        this.mapper = mapper;
        this.clock = clock;
        this.responder = responder;
    }

    @jakarta.annotation.PreDestroy
    void stopPool() { pool.shutdown(); }

    @PostMapping(value = "/interactions", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<?> interact(HttpServletRequest request,
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
        boolean admin = guard.isSlackAdmin(userId);
        if ("view_submission".equals(payload.path("type").asText())) {
            return viewSubmission(payload, userId, admin);
        }

        String responseUrl = payload.path("response_url").asText(null);
        // 홈 현황판 「새로고침」은 보기만 하는 것이라 누구나
        if (SlackHome.ACTION_REFRESH.equals(payload.path("actions").path(0).path("action_id").asText(""))) {
            if (userId != null) home.publish(userId);
            return ResponseEntity.ok().build();
        }
        if (!admin) {
            log.info("슬랙 비관리자 버튼 무시 user={}", userId);
            responder.reply(responseUrl, "상태 변경 권한이 없어요.");
            return ResponseEntity.ok(Map.of("text", "상태 변경 권한이 없어요."));
        }
        JsonNode action = payload.path("actions").path(0);
        String actionId = action.path("action_id").asText("");
        String value = action.path("value").asText("");
        String channel = firstText(payload.path("container").path("channel_id"), payload.path("channel").path("id"));
        String messageTs = firstText(payload.path("container").path("message_ts"), payload.path("message").path("ts"));
        try {
            if (actionId.equals(SlackCards.ACTION_RULES_REVERT)) {
                // 누른 카드를 기억해 두면, 되돌림이 커밋된 뒤 공통 갱신(SlackCardRefresher)이 이 카드를 바꾼다
                store.remember(SlackMessageStore.Kind.RULES, Long.parseLong(value), channel, messageTs);
                rules.revert(Long.parseLong(value), "slack:" + userId);
                return ResponseEntity.ok(Map.of("text", "규칙 #" + value + " 되돌림"));
            }
            if (actionId.equals(SlackCards.ACTION_DIGEST_HANDOFF)) {
                long digestId = Long.parseLong(value);
                // 넘긴 뒤 이 카드가 「넘김 · 링크」로 바뀐다(공통 갱신). 실패하면 누른 사람에게만 이유
                pool.submit(() -> {
                    try {
                        var done = handoff.handoff(digestId, "slack:" + userId, channel, messageTs);
                        if (done.isEmpty()) responder.reply(responseUrl, "넘길 열린 이슈가 없어요(모두 해결됐거나 제보가 없음).");
                    } catch (RuntimeException e) {
                        log.warn("개발 AI에게 넘기기 실패 digest={}: {}", digestId, e.getMessage());
                        responder.reply(responseUrl, "개발 AI에게 넘기지 못했어요: " + e.getMessage());
                    }
                });
                return ResponseEntity.ok().build();
            }
            if (actionId.equals(SlackCards.ACTION_DIGEST_JIRA)) {
                long digestId = Long.parseLong(value);
                pool.submit(() -> {
                    try {
                        var done = jira.handoff(digestId, "slack:" + userId, channel, messageTs);
                        int made = done.stream().mapToInt(r -> r.created().size()).sum();
                        if (made == 0) responder.reply(responseUrl, done.isEmpty()
                                ? "Jira 로 보낼 열린 이슈가 없어요." : "이미 모두 Jira 티켓이 있어요.");
                    } catch (RuntimeException e) {
                        log.warn("Jira 티켓 만들기 실패 digest={}: {}", digestId, e.getMessage());
                        responder.reply(responseUrl, "Jira 티켓을 만들지 못했어요: " + e.getMessage());
                    }
                });
                return ResponseEntity.ok().build();
            }
            if (actionId.equals(SlackCards.ACTION_RECLASSIFY) || actionId.equals(SlackCards.ACTION_DETACH)) {
                long issueId = Long.parseLong(value);
                return openFixView(actionId, issueId, payload.path("trigger_id").asText(null), channel, messageTs, responseUrl);
            }
            String[] parts = value.split(":");
            if (parts.length != 2) return ResponseEntity.badRequest().body(Map.of("error", "버튼 값 형식 오류"));
            long issueId = Long.parseLong(parts[0]);
            IssueStatus next = IssueStatus.valueOf(parts[1]);
            // 이 기능 전에 올린 카드도 바뀌게 누른 카드 위치를 기억해 둔다. 카드 바꾸기는 공통 갱신이 한다(API·AI 로 바꿔도 같은 길)
            store.remember(SlackMessageStore.Kind.ISSUE, issueId, channel, messageTs);
            issues.changeStatus(issueId, next, "slack:" + userId, "slack");
            String done = "이슈 #" + issueId + " → " + next;
            if (channel == null || messageTs == null) responder.reply(responseUrl, done);
            return ResponseEntity.ok(Map.of("text", done));
        } catch (ConflictException | DataIntegrityViolationException e) {
            // 예: 같은 내용의 열린 이슈가 이미 있어 다시 열 수 없음(E8)
            String msg = e instanceof ConflictException ? e.getMessage() : "같은 내용의 열린 이슈가 이미 있어요. 새로고침해 보세요.";
            responder.reply(responseUrl, "처리하지 못했어요: " + msg);
            return ResponseEntity.ok(Map.of("text", msg));
        } catch (NotFoundException e) {
            responder.reply(responseUrl, "이슈를 찾지 못했어요.");
            return ResponseEntity.ok(Map.of("text", e.getMessage()));
        }
    }

    // ── 고치기 입력 창 ──

    /** 창을 저장할 때 원래 카드를 찾아 바꿀 수 있게, 이슈 번호와 카드 위치를 창에 실어 둔다. */
    record Meta(long issueId, String channel, String ts) {
        String encode() { return issueId + "|" + nz(channel) + "|" + nz(ts); }

        static Meta decode(String s) {
            String[] p = (s == null ? "" : s).split("\\|", -1);
            if (p.length != 3) throw new IllegalArgumentException("창 정보 형식 오류");
            return new Meta(Long.parseLong(p[0]), p[1].isEmpty() ? null : p[1], p[2].isEmpty() ? null : p[2]);
        }

        private static String nz(String v) { return v == null ? "" : v; }
    }

    private ResponseEntity<?> openFixView(String actionId, long issueId, String triggerId, String channel, String messageTs,
                                          String responseUrl) {
        IssueCard card = cards.current(issueId);
        if (!card.status().isOpen()) {
            responder.reply(responseUrl, "닫힌 이슈예요. 다시 연 뒤에 고쳐 주세요.");
            return ResponseEntity.ok(Map.of("text", "닫힌 이슈"));
        }
        String meta = new Meta(issueId, channel, messageTs).encode();
        var view = actionId.equals(SlackCards.ACTION_RECLASSIFY)
                ? SlackCards.reclassifyView(card, meta)
                : SlackCards.detachView(card, choices(issueId), meta);
        if (!web.openView(triggerId, view)) responder.reply(responseUrl, "입력 창을 열지 못했어요. 잠시 뒤 다시 눌러 주세요.");
        return ResponseEntity.ok().build();
    }

    private List<SlackCards.ReportChoice> choices(long issueId) {
        return issues.reportsOf(issueId).stream()
                .sorted(Comparator.comparing(IssueReport::getLinkedAt).reversed())
                .limit(10)
                .map(l -> reports.findById(l.getReportId())
                        .map(r -> new SlackCards.ReportChoice(r.getId(), r.getBody(), l.getLinkedAt())).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ResponseEntity<?> viewSubmission(JsonNode payload, String userId, boolean admin) {
        JsonNode view = payload.path("view");
        String callback = view.path("callback_id").asText("");
        JsonNode values = view.path("state").path("values");
        if (!admin) return errors(callback.equals(SlackCards.VIEW_DETACH) ? "report" : "category", "고칠 권한이 없어요.");
        Meta meta;
        try {
            meta = Meta.decode(view.path("private_metadata").asText(null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "창 정보 형식 오류"));
        }
        String reason = blankToNull(values.path("reason").path("v").path("value").asText(null));
        String actor = "slack:" + userId;
        try {
            if (callback.equals(SlackCards.VIEW_RECLASSIFY)) {
                store.remember(SlackMessageStore.Kind.ISSUE, meta.issueId(), meta.channel(), meta.ts());
                String catV = selected(values, "category"), sevV = selected(values, "severity");
                if (catV == null || sevV == null) return errors("category", "분류와 심각도를 골라 주세요.");
                Category cat = Category.valueOf(catV);
                Severity sev = Severity.valueOf(sevV);
                corrections.reclassify(meta.issueId(), cat, sev, actor, reason);
            } else if (callback.equals(SlackCards.VIEW_DETACH)) {
                String picked = selected(values, "report");
                if (picked == null) return errors("report", "떼어낼 제보를 골라 주세요.");
                store.remember(SlackMessageStore.Kind.ISSUE, meta.issueId(), meta.channel(), meta.ts());
                corrections.detach(meta.issueId(), Long.parseLong(picked), actor, reason);
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "모르는 입력 창: " + callback));
            }
        } catch (ConflictException | NotFoundException | IllegalArgumentException e) {
            return errors(callback.equals(SlackCards.VIEW_DETACH) ? "report" : "category", e.getMessage());
        }
        // 빈 200 = 창 닫기
        return ResponseEntity.ok().build();
    }

    private static String selected(JsonNode values, String block) {
        JsonNode v = values.path(block).path("v").path("selected_option").path("value");
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static ResponseEntity<?> errors(String block, String message) {
        return ResponseEntity.ok(Map.of("response_action", "errors", "errors", Map.of(block, SlackCards.cut(message, 150))));
    }

    // ── 문구 ──


    /** 「무엇 · 누가 · 언제」. 누가는 슬랙 멘션 형식(이름으로 보임), 서명된 요청의 ID 라도 모양을 확인하고 넣는다. */
    String actionLine(String what, String userId) {
        String who = userId != null && SLACK_ID.matcher(userId).matches() ? "<@" + userId + ">" : "누군가";
        return what + " · " + who + " · " + SlackCards.when(OffsetDateTime.now(clock));
    }

    private static String firstText(JsonNode a, JsonNode b) {
        if (a != null && a.isTextual() && !a.asText().isBlank()) return a.asText();
        if (b != null && b.isTextual() && !b.asText().isBlank()) return b.asText();
        return null;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

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
