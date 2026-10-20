// 평가 묶음 읽기 — eval/classifier-cases.jsonl(분류 정답), eval/duplicate-pairs.jsonl(같은 문제인지 정답)
package com.ljh.gameops.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class EvalData {
    private static final ObjectMapper M = new ObjectMapper();

    public record Case(int id, String split, String game, String body, Category category, Severity severity, String note) {}

    public record Pair(int id, String a, String b, boolean same) {}

    private EvalData() {}

    public static List<Case> cases(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).map(l -> {
            try {
                var n = M.readTree(l);
                return new Case(n.get("id").asInt(), n.get("split").asText(), n.get("game").asText(), n.get("body").asText(),
                        Category.valueOf(n.get("category").asText()), Severity.valueOf(n.get("severity").asText()),
                        n.hasNonNull("note") ? n.get("note").asText() : null);
            } catch (IOException e) {
                throw new IllegalArgumentException("평가 묶음 줄 오류: " + l, e);
            }
        }).toList();
    }

    public static List<Pair> pairs(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).map(l -> {
            try {
                var n = M.readTree(l);
                return new Pair(n.get("id").asInt(), n.get("a").asText(), n.get("b").asText(), n.get("same").asBoolean());
            } catch (IOException e) {
                throw new IllegalArgumentException("평가 묶음 줄 오류: " + l, e);
            }
        }).toList();
    }
}
