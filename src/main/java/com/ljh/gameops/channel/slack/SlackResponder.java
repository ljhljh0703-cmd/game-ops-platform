// 슬랙 버튼을 누른 사람에게 결과를 알려 준다 — 버튼 요청의 응답 본문은 슬랙이 무시하므로 response_url 로 보낸다
package com.ljh.gameops.channel.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class SlackResponder {
    private static final Logger log = LoggerFactory.getLogger(SlackResponder.class);
    private final RestClient client = RestClient.create();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    /** 슬랙 주소가 아니면 보내지 않는다(요청 위조로 서버가 임의 주소를 부르게 되는 것 방지). */
    static boolean isSlackUrl(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url);
            return "https".equals(u.getScheme()) && "hooks.slack.com".equals(u.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() { pool.shutdown(); }

    public boolean reply(String responseUrl, String text) {
        if (!isSlackUrl(responseUrl)) return false;
        pool.submit(() -> {
            try {
                client.post().uri(responseUrl).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("response_type", "ephemeral", "replace_original", false, "text", text))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("슬랙 버튼 답장 실패: {}", e.getMessage());
            }
        });
        return true;
    }
}
