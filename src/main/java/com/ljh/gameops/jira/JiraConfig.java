// Jira 연결 설정 — gameops.jira.base-url·email·api-token. 하나라도 비면 「Jira 설정이 없어요」를 알려 주는 빈 연결
package com.ljh.gameops.jira;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class JiraConfig {
    static final String KEY = "[A-Z][A-Z0-9_]{0,19}";

    @Bean
    JiraClient jiraClient(@Value("${gameops.jira.base-url:}") String baseUrl,
                          @Value("${gameops.jira.email:}") String email,
                          @Value("${gameops.jira.api-token:}") String token) {
        if (blank(baseUrl) || blank(email) || blank(token)) return off();
        String base = baseUrl.strip().replaceAll("/+$", "");
        if (!base.startsWith("https://")) throw new IllegalStateException("JIRA_BASE_URL 은 https:// 로 시작해야 해요");
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(20));
        String basic = Base64.getEncoder().encodeToString((email.strip() + ":" + token.strip()).getBytes(StandardCharsets.UTF_8));
        RestClient client = RestClient.builder().baseUrl(base).requestFactory(rf)
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", "application/json").build();
        return new JiraClient() {
            public Created create(String project, String summary, Map<String, Object> description, String priority, List<String> labels) {
                if (project == null || !project.matches(KEY)) throw new IllegalArgumentException("Jira 프로젝트 키 형식 오류: " + project);
                Map<String, Object> fields = new HashMap<>();
                fields.put("project", Map.of("key", project));
                fields.put("issuetype", Map.of("name", "Bug"));
                fields.put("summary", summary);
                fields.put("description", description);
                fields.put("labels", labels);
                if (priority != null) fields.put("priority", Map.of("name", priority));
                try {
                    return post(fields);
                } catch (RestClientResponseException e) {
                    // 우선순위 칸이 없는 프로젝트면 우선순위만 빼고 다시(라벨에는 심각도가 남는다)
                    if (e.getStatusCode().value() == 400 && priority != null && e.getResponseBodyAsString().contains("priority")) {
                        fields.remove("priority");
                        try {
                            return post(fields);
                        } catch (RestClientResponseException e2) {
                            throw explain(e2, project);
                        }
                    }
                    throw explain(e, project);
                }
            }

            private Created post(Map<String, Object> fields) {
                JsonNode r = client.post().uri("/rest/api/3/issue").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("fields", fields)).retrieve().body(JsonNode.class);
                String key = r.path("key").asText();
                return new Created(key, browseUrl(key));
            }

            public Status status(String key) {
                JsonNode r = client.get().uri("/rest/api/3/issue/" + key + "?fields=status,assignee")
                        .retrieve().body(JsonNode.class);
                JsonNode st = r.path("fields").path("status");
                String who = r.path("fields").path("assignee").path("displayName").asText(null);
                return new Status("done".equals(st.path("statusCategory").path("key").asText()), st.path("name").asText(""), who);
            }

            public boolean attach(String key, String filename, byte[] data) {
                try {
                    var parts = new LinkedMultiValueMap<String, Object>();
                    parts.add("file", new ByteArrayResource(data) {
                        @Override public String getFilename() { return filename; }
                    });
                    client.post().uri("/rest/api/3/issue/" + key + "/attachments").header("X-Atlassian-Token", "no-check")
                            .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().toBodilessEntity();
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }

            public boolean ready() { return true; }

            public String browseUrl(String key) { return base + "/browse/" + key; }
        };
    }

    static IllegalStateException explain(RestClientResponseException e, String project) {
        int code = e.getStatusCode().value();
        String why = code == 401 ? "Jira 메일이나 API 토큰이 틀렸어요"
                : code == 403 ? "이 계정에 " + project + " 프로젝트 티켓 만들기 권한이 없어요"
                : code == 404 ? project + " 프로젝트를 찾지 못했어요"
                : code == 400 ? "Jira 가 티켓 내용을 받지 않았어요(이 프로젝트에 Bug 유형이 있는지 확인)"
                : "Jira 응답 " + code;
        return new IllegalStateException(why, e);
    }

    static JiraClient off() {
        return new JiraClient() {
            public Created create(String p, String s, Map<String, Object> d, String pr, List<String> l) {
                throw new IllegalStateException("Jira 설정이 없어요(.env 의 JIRA_BASE_URL·JIRA_EMAIL·JIRA_API_TOKEN)");
            }
            public Status status(String key) { throw new IllegalStateException("Jira 설정이 없어요"); }
            public boolean attach(String key, String filename, byte[] data) { return false; }
            public boolean ready() { return false; }
            public String browseUrl(String key) { return key; }
        };
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
