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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class GitHubConfig {
    @Bean
    GitHubIssues gitHubIssues(@Value("${gameops.github.token:}") String token,
                              @Value("${gameops.github.api-url:https://api.github.com}") String apiUrl,
                              com.ljh.gameops.ai.ApiQuota quota) {
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
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .requestInterceptor((req, body, exec) -> { // 응답마다 남은 요청 수를 기록(사용량 화면)
                    var resp = exec.execute(req, body);
                    quota.updateGitHub(resp.getHeaders());
                    return resp;
                }).build();
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
            public Created openPr(String repo, String branch, String title, String body) {
                checkRepo(repo);
                try {
                    JsonNode info = client.get().uri("/repos/" + repo).retrieve().body(JsonNode.class);
                    String base = info == null ? "main" : info.path("default_branch").asText("main");
                    JsonNode r = client.post().uri("/repos/" + repo + "/pulls").contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("title", title, "head", branch, "base", base, "body", body))
                            .retrieve().body(JsonNode.class);
                    return new Created(r.path("number").asInt(), r.path("html_url").asText());
                } catch (RestClientResponseException e) {
                    throw new IllegalStateException(why(e, repo, "PR 만들기(Pull requests 쓰기)"), e);
                }
            }

            public void mergePr(String repo, int number, String title) {
                checkRepo(repo);
                try {
                    client.put().uri("/repos/" + repo + "/pulls/" + number + "/merge").contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("merge_method", "squash", "commit_title", title + " (#" + number + ")"))
                            .retrieve().toBodilessEntity();
                } catch (RestClientResponseException e) {
                    int code = e.getStatusCode().value();
                    if (code == 405) throw new IllegalStateException("깃허브가 지금은 합칠 수 없대요(검사 실패나 충돌). PR 화면에서 확인해 주세요", e);
                    if (code == 409) throw new IllegalStateException("기본 브랜치가 그새 바뀌어 충돌이 났어요. PR 화면에서 확인해 주세요", e);
                    throw new IllegalStateException(why(e, repo, "합치기(Contents 쓰기)"), e);
                }
            }

            public boolean ready() { return true; }
        };
    }

    private static void checkRepo(String repo) {
        if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) throw new IllegalArgumentException("저장소 이름 형식 오류: " + repo);
    }

    private static String why(RestClientResponseException e, String repo, String what) {
        int code = e.getStatusCode().value();
        return code == 401 ? "토큰이 틀렸거나 만료됐어요"
                : code == 403 || code == 404 ? "토큰에 " + repo + " " + what + " 권한이 없어요"
                : code == 422 ? "깃허브가 요청을 받지 않았어요(이미 같은 PR 이 있거나 바뀐 것이 없음)"
                : "깃허브 응답 " + code;
    }

    @Bean
    GitHubRepoReader gitHubRepoReader(@Value("${gameops.github.token:}") String token,
                                      @Value("${gameops.github.api-url:https://api.github.com}") String apiUrl,
                                      com.ljh.gameops.ai.ApiQuota quota) {
        if (token == null || token.isBlank()) {
            return new GitHubRepoReader() {
                public List<Merged> recentlyMerged(String repo) { return List.of(); }
                public List<Commit> commitsSince(String repo, OffsetDateTime since) { return List.of(); }
                public boolean comment(String repo, int number, String body) { return false; }
                public boolean ready() { return false; }
            };
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(15));
        RestClient client = RestClient.builder().baseUrl(apiUrl).requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + token.strip())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .requestInterceptor((req, body, exec) -> { // 응답마다 남은 요청 수를 기록(사용량 화면)
                    var resp = exec.execute(req, body);
                    quota.updateGitHub(resp.getHeaders());
                    return resp;
                }).build();
        return new GitHubRepoReader() {
            public List<Merged> recentlyMerged(String repo) {
                JsonNode arr = client.get().uri("/repos/" + repo + "/pulls?state=closed&sort=updated&direction=desc&per_page=20")
                        .retrieve().body(JsonNode.class);
                List<Merged> out = new ArrayList<>();
                if (arr == null) return out;
                for (JsonNode p : arr) {
                    if (p.path("merged_at").isNull() || p.path("merged_at").asText("").isEmpty()) continue;
                    int n = p.path("number").asInt();
                    // 목록에는 합친 사람이 없어 한 건만 더 묻는다(새로 합쳐진 것만 이 길로 온다)
                    String by = p.path("user").path("login").asText(null);
                    try {
                        JsonNode one = client.get().uri("/repos/" + repo + "/pulls/" + n).retrieve().body(JsonNode.class);
                        if (one != null && !one.path("merged_by").isNull()) by = one.path("merged_by").path("login").asText(by);
                    } catch (RuntimeException ignore) {}
                    out.add(new Merged(n, p.path("title").asText(""), p.path("body").asText(""), by,
                            OffsetDateTime.parse(p.path("merged_at").asText()), p.path("html_url").asText()));
                }
                return out;
            }

            public List<Commit> commitsSince(String repo, OffsetDateTime since) {
                JsonNode arr = client.get().uri("/repos/" + repo + "/commits?per_page=30&since=" + since.toInstant())
                        .retrieve().body(JsonNode.class);
                List<Commit> out = new ArrayList<>();
                if (arr == null) return out;
                for (JsonNode c : arr) {
                    String who = c.path("author").path("login").asText(c.path("commit").path("author").path("name").asText("?"));
                    out.add(new Commit(c.path("sha").asText(), c.path("commit").path("message").asText(""), who,
                            OffsetDateTime.parse(c.path("commit").path("committer").path("date").asText()), c.path("html_url").asText()));
                }
                return out;
            }

            public boolean comment(String repo, int number, String body) {
                try {
                    client.post().uri("/repos/" + repo + "/issues/" + number + "/comments").contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("body", body)).retrieve().toBodilessEntity();
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }

            public boolean ready() { return true; }
        };
    }
}
