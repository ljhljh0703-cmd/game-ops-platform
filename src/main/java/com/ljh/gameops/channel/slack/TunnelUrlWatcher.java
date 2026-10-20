// 슬랙 버튼 주소 지킴이 — 임시 공개 주소(trycloudflare)가 바뀌면 슬랙 긴급 채널에 새 주소를 알린다.
// 터널이 저절로 다시 켜지면(맥 재시동·OrbStack 재시작) 주소가 바뀌어 버튼이 조용히 안 먹게 되는데, 그걸 사람이 바로 알게 한다.
// 같은 주소는 한 번만 알린다(대기열 dedupe 키 = 주소라 서버를 다시 켜도 중복 없음). 터널이 꺼져 있으면 아무것도 하지 않는다.
package com.ljh.gameops.channel.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
@ConditionalOnExpression("'${gameops.slack.tunnel-metrics-url:}' != ''")
public class TunnelUrlWatcher {
    private static final Logger log = LoggerFactory.getLogger(TunnelUrlWatcher.class);
    // 임시 주소는 「단어-단어-…」 모양. 접속 실패 때 비거나 api.trycloudflare.com 이 오면 거른다(start.sh 와 같은 기준)
    static final Pattern QUICK_HOST = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)+\\.trycloudflare\\.com");

    private final Supplier<String> hostname;
    private final OutboxService outbox;
    private String lastSeen;

    // 생성자가 둘이라 서버가 쓸 쪽을 꼭 표시한다(빠지면 서버가 켜지다 멈춘다, 2026-10-21 실제로 있었음)
    @Autowired
    public TunnelUrlWatcher(@Value("${gameops.slack.tunnel-metrics-url}") String metricsUrl, OutboxService outbox, ObjectMapper mapper) {
        this(fetcher(metricsUrl, mapper), outbox);
    }

    TunnelUrlWatcher(Supplier<String> hostname, OutboxService outbox) {
        this.hostname = hostname;
        this.outbox = outbox;
    }

    @Scheduled(initialDelayString = "${gameops.slack.tunnel-check-ms:60000}", fixedDelayString = "${gameops.slack.tunnel-check-ms:60000}")
    public void check() {
        String host;
        try {
            host = hostname.get();
        } catch (Exception e) {
            log.debug("터널 주소 확인 실패(터널이 꺼져 있으면 정상): {}", e.getMessage());
            return;
        }
        if (host == null || !QUICK_HOST.matcher(host).matches() || host.equals(lastSeen)) return;
        String url = "https://" + host + "/slack/interactions";
        String events = "https://" + host + "/slack/events";
        String text = """
                :warning: *슬랙 주소가 바뀌었습니다*. 바꾸기 전에는 카드 버튼과 홈 현황판이 안 먹습니다.
                1) 슬랙 앱 설정 > Interactivity & Shortcuts > Request URL
                `%s`
                2) 슬랙 앱 설정 > Event Subscriptions > Request URL
                `%s`""".formatted(url, events);
        if (outbox.enqueueOnce(NotificationType.SLACK_URL_CHANGED, Map.of("text", text, "url", url, "eventsUrl", events), "slack-url:" + host)) {
            log.warn("슬랙 버튼 주소가 바뀌어 알림을 넣었습니다: {}", url);
        }
        lastSeen = host;
    }

    private static Supplier<String> fetcher(String metricsUrl, ObjectMapper mapper) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(3));
        rf.setReadTimeout(Duration.ofSeconds(3));
        RestClient client = RestClient.builder().requestFactory(rf).build();
        return () -> {
            String body = client.get().uri(metricsUrl).retrieve().body(String.class);
            try {
                return mapper.readTree(body == null ? "{}" : body).path("hostname").asText(null);
            } catch (Exception e) {
                throw new IllegalStateException("터널 응답 해석 실패", e);
            }
        };
    }
}
