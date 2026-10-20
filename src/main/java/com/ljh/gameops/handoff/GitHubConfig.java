// 깃허브 연결 설정 — gameops.github.token(GITHUB_TOKEN). 토큰이 없으면 「토큰이 없어요」를 알려 주는 빈 연결
package com.ljh.gameops.handoff;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
public class GitHubConfig {
    @Bean
    GitHubIssues gitHubIssues(@Value("${gameops.github.token:}") String token,
                              @Value("${gameops.github.api-url:https://api.github.com}") String apiUrl) {
        if (token == null || token.isBlank()) {
            return new GitHubIssues() {
                public Created create(String repo, String title, String body, List<String> labels) {
                    throw new IllegalStateException("깃허브 토큰이 없어요(.env 의 GITHUB_TOKEN)");
                }
                public boolean ready() { return false; }
            };
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(20));
        RestClient client = RestClient.builder().baseUrl(apiUrl).requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + token.strip())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28").build();
        return new GitHubIssues() {
            public Created create(String repo, String title, String body, List<String> labels) {
                if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) throw new IllegalArgumentException("저장소 이름 형식 오류: " + repo);
                try {
                    JsonNode r = client.post().uri("/repos/" + repo + "/issues").contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("title", title, "body", body, "labels", labels))
                            .retrieve().body(JsonNode.class);
                    return new Created(r.path("number").asInt(), r.path("html_url").asText());
                } catch (RestClientResponseException e) {
                    int code = e.getStatusCode().value();
                    String why = code == 401 ? "토큰이 틀렸거나 만료됐어요"
                            : code == 403 || code == 404 ? "토큰에 " + repo + " 이슈 쓰기 권한이 없어요"
                            : code == 410 ? repo + " 저장소의 이슈 기능이 꺼져 있어요" : "깃허브 응답 " + code;
                    throw new IllegalStateException(why, e);
                }
            }
            public boolean ready() { return true; }
        };
    }
}
