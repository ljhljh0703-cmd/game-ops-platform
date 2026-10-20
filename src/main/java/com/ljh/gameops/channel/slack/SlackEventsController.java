// 슬랙 이벤트 받기 — 지금은 「앱 홈 탭을 열었다(app_home_opened)」 하나. 서명 확인 후 그 사람 홈에 현황판을 올린다.
// 슬랙 앱 설정에서 이벤트 주소를 처음 넣을 때 오는 확인(url_verification)에도 답한다. 3초 안에 답해야 해서 올리기는 뒤에서
package com.ljh.gameops.channel.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/slack")
@Hidden
public class SlackEventsController {
    private static final Logger log = LoggerFactory.getLogger(SlackEventsController.class);
    private final SlackProperties props;
    private final SlackHomeService home;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    public SlackEventsController(SlackProperties props, SlackHomeService home, ObjectMapper mapper, Clock clock) {
        this.props = props;
        this.home = home;
        this.mapper = mapper;
        this.clock = clock;
    }

    @jakarta.annotation.PreDestroy
    void stop() { pool.shutdown(); }

    @PostMapping(value = "/events", consumes = "application/json")
    public ResponseEntity<?> events(HttpServletRequest request,
                                    @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String ts,
                                    @RequestHeader(value = "X-Slack-Signature", required = false) String sig) throws Exception {
        String raw = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        if (props.signingSecret() == null || props.signingSecret().isBlank()
                || !new SlackSignatureVerifier(props.signingSecret()).verify(ts, raw, sig, clock.instant())) {
            return ResponseEntity.status(401).body(Map.of("error", "서명 불일치"));
        }
        JsonNode p = mapper.readTree(raw);
        if ("url_verification".equals(p.path("type").asText())) {
            return ResponseEntity.ok(Map.of("challenge", p.path("challenge").asText("")));
        }
        JsonNode e = p.path("event");
        if ("app_home_opened".equals(e.path("type").asText()) && "home".equals(e.path("tab").asText("home"))) {
            String user = e.path("user").asText(null);
            if (user != null) pool.submit(() -> {
                try {
                    home.publish(user);
                } catch (RuntimeException ex) {
                    log.warn("슬랙 홈 올리기 실패 user={}: {}", user, ex.getMessage());
                }
            });
        }
        return ResponseEntity.ok().build();
    }
}
