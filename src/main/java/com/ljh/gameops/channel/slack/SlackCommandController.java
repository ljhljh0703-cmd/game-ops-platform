// 슬랙 명령 /피드백정리 — 슬랙에서 한 줄로 「최근 피드백 정리 보고서」를 올린다(결정 28).
// 쓰는 법: /피드백정리 [게임] [기간]  예) /피드백정리 · /피드백정리 젤리패닉 3 · /피드백정리 보석타워 14일
//         /피드백정리 지표 [게임] [기간] — 처리 지표(결정 35) · /피드백정리 사용량 — 이번 달 AI 비용·예산·깃허브 남은 요청(결정 34)
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
        Map<String, String> f = form(raw);
        String user = f.getOrDefault("user_id", "");
        if (!guard.isSlackAdmin(user)) return reply("이 명령은 운영 담당만 쓸 수 있어요.");

        String text = f.getOrDefault("text", "").strip();
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
