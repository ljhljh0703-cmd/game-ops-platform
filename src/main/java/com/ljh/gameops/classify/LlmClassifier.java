// OpenAI 호환 API로 분류하는 분류기 — JSON 한 줄만 받아 검증한다
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class LlmClassifier implements IssueClassifier {

    private static final String SYSTEM_PROMPT = """
            You classify player reports for a game operations team.
            Reply with ONE JSON object and nothing else:
            {"category": one of %s, "severity": one of %s, "title": short Korean title under 60 chars}
            Use CRITICAL only for payment loss or data loss. Use HIGH for crashes or being unable to play.
            """.formatted(List.of(Category.values()), List.of(Severity.values()));

    private final RestClient client;
    private final String model;
    private final ObjectMapper mapper;

    public LlmClassifier(RestClient client, String model, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public Classification classify(String gameCode, String body) {
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", "game=" + gameCode + "\nreport:\n" + body)));
        JsonNode response;
        try {
            response = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            throw new ClassifierException("LLM 호출 실패", e);
        }
        return parse(response);
    }

    Classification parse(JsonNode response) {
        try {
            String content = response.path("choices").path(0).path("message").path("content").asText();
            String json = content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1);
            JsonNode node = mapper.readTree(json);
            Category category = Category.valueOf(node.path("category").asText());
            Severity severity = Severity.valueOf(node.path("severity").asText());
            String title = node.path("title").asText("").strip();
            if (title.isEmpty()) throw new ClassifierException("빈 제목");
            if (title.length() > Titles.MAX) title = title.substring(0, Titles.MAX - 1) + "…";
            return new Classification(category, severity, title, "llm");
        } catch (ClassifierException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassifierException("LLM 응답 형식 오류", e);
        }
    }
}
