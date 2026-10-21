// 슬랙 소켓 모드(결정 40) — 서버가 슬랙에 먼저 연결해 두고 그 줄로 버튼·입력 창·명령·홈 탭 이벤트를 받는다.
// 공개 주소·중계(nginx)·임시 터널이 필요 없어서, 서버를 껐다 켜도 슬랙 앱 설정의 주소를 바꿀 일이 없다.
// 앱 토큰(SLACK_APP_TOKEN, xapp-…, connections:write)이 있을 때만 켜진다. 없으면 예전처럼 주소(HTTP)로 받는다.
// 받은 것은 HTTP 로 받을 때와 같은 처리기로 넘기고(서명 확인 대신 슬랙이 연 연결이라는 것으로 믿는다), 처리기 답을 그대로 돌려준다
package com.ljh.gameops.channel.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.api.Slack;
import com.slack.api.socket_mode.SocketModeClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class SlackSocketMode {
    private static final Logger log = LoggerFactory.getLogger(SlackSocketMode.class);

    private final String appToken;
    private final SlackInteractionController interactions;
    private final SlackCommandController commands;
    private final SlackEventsController events;
    private final ObjectMapper mapper;
    private SocketModeClient client;

    public SlackSocketMode(@Value("${gameops.slack.app-token:}") String appToken, SlackInteractionController interactions,
                           SlackCommandController commands, SlackEventsController events, ObjectMapper mapper) {
        this.appToken = appToken == null ? "" : appToken.strip();
        this.interactions = interactions;
        this.commands = commands;
        this.events = events;
        this.mapper = mapper;
    }

    public boolean enabled() { return !appToken.isEmpty(); }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled()) return;
        try {
            client = Slack.getInstance().socketMode(appToken, SocketModeClient.Backend.JavaWebSocket);
            client.addWebSocketMessageListener(this::onMessage);
            client.setAutoReconnectEnabled(true);
            client.connect();
            log.info("슬랙 소켓 모드로 연결했어요(공개 주소 없이 버튼·명령·이벤트를 받음)");
        } catch (Exception e) {
            // 토큰이 틀렸거나 슬랙 앱에서 소켓 모드가 꺼져 있으면 여기. 서버는 계속 돈다(알림 보내기는 그대로)
            log.error("슬랙 소켓 모드 연결 실패: {} — 슬랙 앱 설정 > Socket Mode 가 켜져 있는지, SLACK_APP_TOKEN(xapp-…)이 맞는지 확인", e.getMessage());
        }
    }

    /** 슬랙이 보낸 봉투 하나 → 처리기 → 같은 envelope_id 로 답(3초 안) */
    void onMessage(String raw) {
        try {
            JsonNode env = mapper.readTree(raw);
            String id = env.path("envelope_id").asText(null);
            if (id == null) return; // hello·disconnect 같은 연결 관리 글
            Object payload = dispatch(env.path("type").asText(""), env.path("payload"));
            Map<String, Object> ack = new HashMap<>();
            ack.put("envelope_id", id);
            if (payload != null) ack.put("payload", payload);
            client.sendSocketModeResponse(mapper.writeValueAsString(ack));
        } catch (Exception e) {
            log.warn("슬랙 소켓 모드 처리 실패: {}", e.getMessage());
        }
    }

    /** 처리기가 돌려준 본문(슬랙에 갈 답). 없으면 null */
    Object dispatch(String type, JsonNode payload) {
        ResponseEntity<?> r = switch (type) {
            case "interactive" -> interactions.handle(payload);
            case "slash_commands" -> commands.handle(form(payload));
            case "events_api" -> {
                events.handle(payload);
                yield null;
            }
            default -> null;
        };
        return r == null || !r.getStatusCode().is2xxSuccessful() ? null : r.getBody();
    }

    /** 명령은 소켓 모드에서 JSON 으로 온다 → HTTP 폼과 같은 칸 모양으로 */
    static Map<String, String> form(JsonNode p) {
        Map<String, String> f = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().isValueNode()) f.put(e.getKey(), e.getValue().asText());
        }
        return f;
    }

    @PreDestroy
    public void stop() {
        try {
            if (client != null) client.close();
        } catch (Exception ignore) {}
    }
}
