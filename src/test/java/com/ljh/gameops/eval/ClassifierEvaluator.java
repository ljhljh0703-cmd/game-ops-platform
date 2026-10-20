// 분류 평가 — 분류·심각도 정확도, 분류별 정밀도·재현율, 헷갈린 표, 틀린 사례
package com.ljh.gameops.eval;

import com.ljh.gameops.classify.Classification;
import com.ljh.gameops.classify.IssueClassifier;
import com.ljh.gameops.issue.Category;

import java.util.*;

public final class ClassifierEvaluator {
    public record Miss(EvalData.Case c, Classification got) {}

    public record Result(String split, int total, int categoryOk, int severityOk, int bothOk,
                         Map<Category, Map<Category, Integer>> confusion, List<Miss> misses) {
        public double categoryAccuracy() { return total == 0 ? 0 : (double) categoryOk / total; }
        public double severityAccuracy() { return total == 0 ? 0 : (double) severityOk / total; }
        public double bothAccuracy() { return total == 0 ? 0 : (double) bothOk / total; }

        public double precision(Category c) {
            int predicted = confusion.values().stream().mapToInt(row -> row.getOrDefault(c, 0)).sum();
            return predicted == 0 ? Double.NaN : (double) confusion.get(c).getOrDefault(c, 0) / predicted;
        }

        public double recall(Category c) {
            int actual = confusion.get(c).values().stream().mapToInt(Integer::intValue).sum();
            return actual == 0 ? Double.NaN : (double) confusion.get(c).getOrDefault(c, 0) / actual;
        }
    }

    private ClassifierEvaluator() {}

    public static Result run(IssueClassifier classifier, List<EvalData.Case> cases, String split) {
        Map<Category, Map<Category, Integer>> confusion = new EnumMap<>(Category.class);
        for (Category c : Category.values()) confusion.put(c, new EnumMap<>(Category.class));
        int cat = 0, sev = 0, both = 0, total = 0;
        List<Miss> misses = new ArrayList<>();
        for (EvalData.Case c : cases) {
            if (split != null && !split.equals(c.split())) continue;
            total++;
            Classification got = classifier.classify(c.game(), c.body());
            boolean okCat = got.category() == c.category();
            boolean okSev = got.severity() == c.severity();
            if (okCat) cat++;
            if (okSev) sev++;
            if (okCat && okSev) both++;
            else misses.add(new Miss(c, got));
            confusion.get(c.category()).merge(got.category(), 1, Integer::sum);
        }
        return new Result(split == null ? "all" : split, total, cat, sev, both, confusion, misses);
    }
}
