// 평가 묶음 관문 — 운영 중 규칙 파일을 고쳐 저장해도 평가 묶음을 통과해야 받는다(운영 설계서 P11).
// 기준은 깃 관문 테스트(EvalGateTest)와 같다: dev 분류 정확도 95% 이상, 회귀 문장(한 번 틀렸던 문장)은 분류·심각도 전부 맞아야 함.
// holdout 은 넣지 않는다(규칙 고치기 고리에 노출되면 시험지가 새는 셈이라 릴리스 때만 잰다).
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class EvalRuleGate implements RuleGate {
    private static final Logger log = LoggerFactory.getLogger(EvalRuleGate.class);
    public static final double DEV_FLOOR = 0.95;
    static final String DEV_RESOURCE = "gate/classifier-cases.jsonl";
    static final String REGRESSION_RESOURCE = "gate/classifier-regression.jsonl";

    record Case(String game, String body, Category category, Severity severity) {}

    private final List<Case> dev;
    private final List<Case> regression;

    EvalRuleGate(List<Case> dev, List<Case> regression) {
        this.dev = dev;
        this.regression = regression;
    }

    /** 서버에 함께 묶인 평가 묶음으로 만든다. 묶음이 없으면 확인 없이 받는다(경고 한 번). */
    public static RuleGate fromClasspath() {
        try {
            List<Case> dev = read(DEV_RESOURCE);
            List<Case> reg = read(REGRESSION_RESOURCE);
            if (dev.isEmpty() || reg.isEmpty()) throw new IllegalStateException("평가 묶음이 비어 있음");
            return new EvalRuleGate(dev, reg);
        } catch (Exception e) {
            log.warn("규칙 관문용 평가 묶음을 읽지 못해 관문 없이 받습니다: {}", e.getMessage());
            return NONE;
        }
    }

    @Override
    public String reject(List<ClassificationRule> rules) {
        RuleSet set = new RuleSet(rules, "관문 확인", OffsetDateTime.now(), null);
        var classifier = new RuleBasedClassifier(() -> set);

        List<String> regMiss = new ArrayList<>();
        for (Case c : regression) {
            var got = classifier.classify(c.game(), c.body());
            if (got.category() != c.category() || got.severity() != c.severity()) {
                regMiss.add("「%s」 %s/%s → %s/%s".formatted(c.body(), c.category(), c.severity(), got.category(), got.severity()));
            }
        }
        int ok = 0;
        for (Case c : dev) if (classifier.classify(c.game(), c.body()).category() == c.category()) ok++;
        double acc = (double) ok / dev.size();

        List<String> why = new ArrayList<>();
        if (!regMiss.isEmpty()) {
            why.add("회귀 문장 %d개가 다시 틀림: %s%s".formatted(regMiss.size(),
                    String.join(", ", regMiss.subList(0, Math.min(3, regMiss.size()))), regMiss.size() > 3 ? " 외" : ""));
        }
        if (acc < DEV_FLOOR) {
            why.add("dev 분류 정확도 %.0f%% (기준 %.0f%%)".formatted(acc * 100, DEV_FLOOR * 100));
        }
        return why.isEmpty() ? null : "평가 관문 미달 — " + String.join(" / ", why);
    }

    private static List<Case> read(String resource) throws Exception {
        ObjectMapper m = new ObjectMapper();
        try (InputStream in = EvalRuleGate.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("없음: " + resource);
            List<Case> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                JsonNode n = m.readTree(line);
                String split = n.path("split").asText();
                if (!split.equals("dev") && !split.equals("regression")) continue;
                out.add(new Case(n.path("game").asText(), n.path("body").asText(),
                        Category.valueOf(n.path("category").asText()), Severity.valueOf(n.path("severity").asText())));
            }
            return out;
        }
    }
}
