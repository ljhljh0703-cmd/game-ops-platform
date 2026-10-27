// 슬랙 명령 /피드백정리 — 슬랙에서 한 줄로 「최근 피드백 정리 보고서」를 올린다(결정 28).
// 쓰는 법: /피드백정리 [게임] [기간]  예) /피드백정리 · /피드백정리 젤리패닉 3 · /피드백정리 보석타워 14일
//         /피드백정리 지표 [게임] [기간] — 처리 지표(결정 35) · /피드백정리 사용량 — 이번 달 AI 비용·예산·깃허브 남은 요청(결정 34)
//         /피드백정리 패치노트 [게임] [기간] — 마지막 공지 뒤 고친 버그·바뀐 점으로 패치노트 초안 카드(결정 39)
// 게임을 안 적으면 명령을 친 채널의 게임(게임 알림 채널이면), 그것도 아니면 전체 게임. 기간 기본 7일(최대 31).
// 서명 확인 → 관리자만 → 3초 안에 「정리 중」 답 → 뒤에서 정리해 카드로 올림(실패·제보 없음은 친 사람에게만 알림).
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.feedback.AutoDigest;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/slack")
@Hidden
public class SlackCommandController {
    private static final Logger log = LoggerFactory.getLogger(SlackCommandController.class);

    private final SlackProperties props;
    private final AdminGuard guard;
    private final AutoDigest digest;
    private final GameCatalog games;
    private final SlackResponder responder;
    private final Clock clock;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    @org.springframework.beans.factory.annotation.Autowired(required = false) com.ljh.gameops.metrics.OpsMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false) com.ljh.gameops.ai.AiUsage usage;
    @org.springframework.beans.factory.annotation.Autowired(required = false) com.ljh.gameops.ai.ApiQuota quota;
    @org.springframework.beans.factory.annotation.Autowired(required = false) com.ljh.gameops.patch.PatchNoteService patches;

    public SlackCommandController(SlackProperties props, AdminGuard guard, AutoDigest digest, GameCatalog games,
                                  SlackResponder responder, Clock clock) {
        this.props = props;
        this.guard = guard;
        this.digest = digest;
        this.games = games;
        this.responder = responder;
        this.clock = clock;
    }

    @jakarta.annotation.PreDestroy
    void stop() { pool.shutdown(); }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.ljh.gameops.intake.HeldReleaser heldReleaser;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    org.springframework.context.ApplicationEventPublisher events;

    /** 명령어 모음(결정 47) */
    static final String HELP = """
            *🤖 /피드백정리 명령어*
            `/피드백정리 [게임] [기간]` 최근 제보 정리 카드 (예: 보석타워 7)
            `/피드백정리 이슈 [게임]` 열린 이슈를 급한 순으로
            `/피드백정리 지표 [게임] [기간]` 처리 지표 (고칠 때까지·QA 확인까지)
            `/피드백정리 패치노트 [게임]` 패치노트 초안 카드
            `/피드백정리 사용량` 이번 달 AI 비용·예산
            `/피드백정리 보류함` 몰릴 때 받아 둔 제보 수, 풀 수 있으면 바로 접수
            `/피드백정리 놓친제보` 디스코드 채널 기록을 다시 읽어 빠진 제보 받기
            `/피드백정리 도움말` 이 목록
            게임을 안 적으면 게임 알림 채널에서는 그 게임, 아니면 전체.
            플레이어용 디스코드 명령: /제보 · /알려진문제 · /내제보 · /패치노트 · /도움말""";

    /** 명령 뒤 글에서 읽은 범위 */
    record Scope(GameSpec game, int days, String error) {}

    @PostMapping(value = "/commands", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<?> command(HttpServletRequest request,
                                     @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String ts,
                                     @RequestHeader(value = "X-Slack-Signature", required = false) String sig) throws Exception {
        String raw = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        if (props.signingSecret() == null || props.signingSecret().isBlank()
                || !new SlackSignatureVerifier(props.signingSecret()).verify(ts, raw, sig, clock.instant())) {
            return ResponseEntity.status(401).body(Map.of("error", "서명 불일치"));
        }
        return handle(form(raw));
    }

    /** 서명 확인이 끝난(또는 소켓 모드로 받은) 명령 하나. f = 슬랙이 보낸 칸들(user_id·text·channel_id·response_url…) */
    public ResponseEntity<?> handle(Map<String, String> f) {
        String user = f.getOrDefault("user_id", "");
        if (!guard.isSlackAdmin(user)) return reply("이 명령은 운영 담당만 쓸 수 있어요.");

        String text = f.getOrDefault("text", "").strip();
        if (text.equals("도움말") || text.equals("help") || text.equals("?")) return reply(HELP);
        if (text.equals("보류함") && jdbc != null) {
            int released = heldReleaser == null ? 0 : heldReleaser.release();
            var rows = jdbc.queryForList("SELECT g.name, count(*) AS n FROM held_report h JOIN game g ON g.id = h.game_id GROUP BY g.name ORDER BY g.name");
            if (rows.isEmpty()) return reply("📦 보류함이 비어 있어요." + (released > 0 ? " 방금 " + released + "건을 접수로 넘겼어요." : ""));
            StringBuilder out = new StringBuilder("📦 보류함(제보가 몰려 잠시 받아 둔 새 사람 제보)\n");
            rows.forEach(r -> out.append("· ").append(r.get("name")).append(" ").append(r.get("n")).append("건\n"));
            if (released > 0) out.append("방금 ").append(released).append("건을 접수로 넘겼어요. ");
            return reply(out.append("몰림이 풀리면 30초마다 차례로 접수돼요.").toString());
        }
        if (text.equals("놓친제보") && events != null) {
            events.publishEvent(new com.ljh.gameops.channel.discord.DiscordGateway.BackfillRequested());
            return reply("📥 디스코드 제보 채널 기록을 다시 읽고 있어요. 새로 받은 게 있으면 긴급 채널에 한 줄로 알려요.");
        }
        if ((text.equals("이슈") || text.startsWith("이슈 ")) && jdbc != null) {
            Scope s = scope(text.substring(2), f.get("channel_id"), f.get("channel_name"));
            if (s.error() != null) return reply(s.error());
            var rows = jdbc.queryForList("""
                    SELECT i.id, i.title, i.severity, i.status, i.report_count, g.name FROM issue i JOIN game g ON g.id = i.game_id
                    WHERE i.status IN ('OPEN','IN_PROGRESS','VERIFYING') AND (CAST(? AS text) IS NULL OR g.code = ?)
                    ORDER BY CASE i.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, i.report_count DESC LIMIT 10""",
                    s.game() == null ? null : s.game().code(), s.game() == null ? null : s.game().code());
            if (rows.isEmpty()) return reply("열린 이슈가 없어요.");
            StringBuilder out = new StringBuilder("📋 열린 이슈 " + (s.game() == null ? "(전체)" : "(" + s.game().name() + ")") + " · 급한 순\n");
            for (var r : rows) {
                var sev = com.ljh.gameops.issue.Severity.valueOf((String) r.get("severity"));
                var st = com.ljh.gameops.issue.IssueStatus.valueOf((String) r.get("status"));
                out.append(SlackCards.dot(sev)).append(" #").append(r.get("id")).append(" ")
                        .append(SlackCards.escape(SlackCards.cut((String) r.get("title"), 60))).append(" · ")
                        .append(com.ljh.gameops.issue.Labels.STATUS.get(st)).append(" · 제보 ").append(r.get("report_count")).append("건")
                        .append(s.game() == null ? " · " + r.get("name") : "").append("\n");
            }
            return reply(out.toString().strip());
        }
        if (text.equals("사용량") && usage != null) {
            String q = quota != null && quota.github() != null ? "\n" + quota.github().line() : "";
            return reply("💸 " + usage.month().line() + q + "\n(서버가 부른 AI 만 셉니다. 맥의 Claude·Codex 는 각자 구독이라 따로예요)");
        }
        if ((text.equals("지표") || text.startsWith("지표 ")) && metrics != null) {
            Scope s = scope(text.substring(2), f.get("channel_id"), f.get("channel_name"));
            if (s.error() != null) return reply(s.error());
            int days = text.matches(".*\\d.*") ? s.days() : 30;
            StringBuilder out = new StringBuilder("📈 처리 지표\n");
            for (var m : s.game() == null ? metrics.all(days) : java.util.List.of(metrics.of(s.game().code(), days))) {
                out.append(m.text()).append("\n\n");
            }
            return reply(out.toString().strip());
        }

        if ((text.equals("패치노트") || text.startsWith("패치노트 ")) && patches != null) {
            Scope s = scope(text.substring(4), f.get("channel_id"), f.get("channel_name"));
            if (s.error() != null) return reply(s.error());
            if (s.game() == null) return reply("어느 게임 패치노트인지 적어 주세요. 예) /피드백정리 패치노트 보석타워 (게임 알림 채널에서 치면 그 게임)");
            int days = text.matches(".*\\d.*") ? s.days() : 14;
            String responseUrl = f.get("response_url");
            String name = s.game().name();
            String code = s.game().code();
            pool.submit(() -> {
                try {
                    var d = patches.draft(code, days, "slack:" + user);
                    if (d.id() == null) responder.reply(responseUrl, name + " 마지막 공지 뒤(최근 " + days + "일) 고친 버그나 합쳐진 변경이 없어요.");
                } catch (RuntimeException e) {
                    log.warn("패치노트 초안 실패: {}", e.getMessage());
                    responder.reply(responseUrl, "패치노트 초안을 못 만들었어요: " + e.getMessage());
                }
            });
            return reply("📝 " + name + " 패치노트 초안을 쓰고 있어요. 잠시 뒤 카드로 올라와요. 공지는 카드에서 확인하고 누르면 나가요.");
        }

        Scope scope = scope(text, f.get("channel_id"), f.get("channel_name"));
        if (scope.error() != null) return reply(scope.error());

        String code = scope.game() == null ? null : scope.game().code();
        String name = scope.game() == null ? "전체 게임" : scope.game().name();
        String responseUrl = f.get("response_url");
        pool.submit(() -> {
            try {
                AutoDigest.Result r = digest.run(code, scope.days(), "slack:" + user);
                if (!r.posted()) responder.reply(responseUrl, "최근 " + scope.days() + "일 " + name + " 제보가 없어서 올릴 정리가 없어요.");
                else if (scope.game() == null) responder.reply(responseUrl, "전체 게임 정리는 공용 알림 채널에 올렸어요.");
            } catch (RuntimeException e) {
                log.warn("슬랙 명령 피드백 정리 실패: {}", e.getMessage());
                responder.reply(responseUrl, "정리를 올리지 못했어요: " + e.getMessage());
            }
        });
        return reply("🧠 " + name + " 최근 " + scope.days() + "일 제보를 정리하고 있어요. 잠시 뒤 카드로 올라와요.");
    }

    /** 「젤리패닉 3」「보석타워 14일」「7」 — 게임은 코드나 이름(띄어쓰기 무시), 숫자는 기간 */
    Scope scope(String text, String channelId, String channelName) {
        GameSpec game = null;
        int days = AutoDigest.DEFAULT_DAYS;
        for (String tok : text.strip().split("\\s+")) {
            if (tok.isBlank()) continue;
            String num = tok.replace("일", "");
            if (num.matches("\\d{1,3}")) {
                int n = Integer.parseInt(num);
                if (n < 1 || n > 31) return new Scope(null, 0, "기간은 1~31일로 적어 주세요.");
                days = n;
                continue;
            }
            Optional<GameSpec> g = byWord(tok);
            if (g.isEmpty()) return new Scope(null, 0, "「" + tok + "」 게임을 못 찾았어요. 예) /피드백정리 젤리패닉 3");
            game = g.get();
        }
        if (game == null) game = byChannel(channelId, channelName).orElse(null);
        return new Scope(game, days, null);
    }

    private Optional<GameSpec> byWord(String word) {
        String w = squash(word);
        return games.all().stream().filter(g -> squash(g.code()).equals(w) || squash(g.name()).equals(w)).findFirst();
    }

    /** 게임 알림 채널에서 친 명령이면 그 게임 */
    private Optional<GameSpec> byChannel(String channelId, String channelName) {
        return games.all().stream().filter(g -> {
            String c = g.slackChannel();
            if (c == null || c.isBlank()) return false;
            return c.equals(channelId) || (channelName != null && squash(c.replaceFirst("^#", "")).equals(squash(channelName)));
        }).findFirst();
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-_]", "").toLowerCase();
    }

    private static ResponseEntity<?> reply(String text) {
        return ResponseEntity.ok(Map.of("response_type", "ephemeral", "text", text));
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> m = new HashMap<>();
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
