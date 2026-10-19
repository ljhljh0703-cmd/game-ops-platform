// 규칙 파일(JSON)을 읽고 검사한다 — 하나라도 틀리면 파일 전체를 받지 않는다
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RuleParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleParser() {}

    public static List<ClassificationRule> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 형식 오류: " + e.getMessage());
        }
        JsonNode rules = root.path("rules");
        if (!rules.isArray() || rules.isEmpty()) throw new IllegalArgumentException("rules 배열이 비어 있거나 없습니다");
        List<ClassificationRule> out = new ArrayList<>();
        int i = 0;
        for (JsonNode r : rules) {
            i++;
            Category c;
            Severity s;
            try {
                c = Category.valueOf(r.path("category").asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(i + "번째 규칙의 category 값이 목록에 없습니다: " + r.path("category").asText());
            }
            try {
                s = Severity.valueOf(r.path("severity").asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(i + "번째 규칙의 severity 값이 목록에 없습니다: " + r.path("severity").asText());
            }
            JsonNode kws = r.path("keywords");
            if (!kws.isArray() || kws.isEmpty()) throw new IllegalArgumentException(i + "번째 규칙에 keywords 가 없습니다");
            List<String> words = new ArrayList<>();
            for (JsonNode k : kws) {
                String w = k.asText("").toLowerCase(Locale.ROOT);
                if (w.isBlank()) throw new IllegalArgumentException(i + "번째 규칙에 빈 키워드가 있습니다");
                words.add(w);
            }
            out.add(new ClassificationRule(c, s, List.copyOf(words)));
        }
        return List.copyOf(out);
    }
}
