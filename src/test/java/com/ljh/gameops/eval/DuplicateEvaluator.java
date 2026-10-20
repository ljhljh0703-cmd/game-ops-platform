// 묶기 기준값 평가 — 운영과 같은 정규화·유사도로 기준값을 바꿔 가며 정밀도(엉뚱하게 묶지 않음)·재현율(같은 건 묶음)을 본다
package com.ljh.gameops.eval;

import com.ljh.gameops.issue.Similarity;
import com.ljh.gameops.issue.TextNormalizer;

import com.ljh.gameops.classify.IssueClassifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DuplicateEvaluator {
    public record Row(double threshold, int tp, int fp, int fn, int tn) {
        public double precision() { return tp + fp == 0 ? Double.NaN : (double) tp / (tp + fp); }
        public double recall() { return tp + fn == 0 ? Double.NaN : (double) tp / (tp + fn); }
        public double f1() {
            double p = precision(), r = recall();
            return Double.isNaN(p) || Double.isNaN(r) || p + r == 0 ? 0 : 2 * p * r / (p + r);
        }
    }

    public record Scored(EvalData.Pair pair, double score) {}

    /** 비교할 유사도 방식. 운영은 JACCARD_SAME_CATEGORY(P9). 나머지는 비교용 후보. */
    public enum Method {
        JACCARD("글자 2개 묶음 자카드(2026-10-20 까지 운영)"),
        JACCARD_SAME_CATEGORY("자카드 + 분류가 같을 때만(운영, P9)"),
        OVERLAP("겹침 계수 — 겹친 묶음 ÷ 짧은 쪽 묶음 수"),
        OVERLAP_SAME_CATEGORY("겹침 계수 + 분류가 같을 때만");

        public final String label;
        Method(String label) { this.label = label; }
    }

    private DuplicateEvaluator() {}

    public static List<Scored> score(List<EvalData.Pair> pairs) {
        return score(pairs, Method.JACCARD, null);
    }

    public static List<Scored> score(List<EvalData.Pair> pairs, Method method, IssueClassifier classifier) {
        return pairs.stream().map(p -> {
            String a = TextNormalizer.normalize(p.a()), b = TextNormalizer.normalize(p.b());
            double s = switch (method) {
                case JACCARD -> Similarity.jaccard(a, b);
                case JACCARD_SAME_CATEGORY -> sameCategory(classifier, p) ? Similarity.jaccard(a, b) : 0.0;
                case OVERLAP -> overlap(a, b);
                case OVERLAP_SAME_CATEGORY -> sameCategory(classifier, p) ? overlap(a, b) : 0.0;
            };
            return new Scored(p, s);
        }).toList();
    }

    private static boolean sameCategory(IssueClassifier c, EvalData.Pair p) {
        return c.classify("eval", p.a()).category() == c.classify("eval", p.b()).category();
    }

    static double overlap(String a, String b) {
        Set<String> x = bigrams(a), y = bigrams(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        return (double) inter.size() / Math.min(x.size(), y.size());
    }

    private static Set<String> bigrams(String s) {
        String t = s.replace(" ", "");
        Set<String> out = new HashSet<>();
        if (t.length() == 1) out.add(t);
        for (int i = 0; i + 2 <= t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }

    public static List<Row> sweep(List<Scored> scored, double from, double to, double step) {
        List<Row> rows = new ArrayList<>();
        for (double t = from; t <= to + 1e-9; t += step) {
            double th = Math.round(t * 100) / 100.0;
            int tp = 0, fp = 0, fn = 0, tn = 0;
            for (Scored s : scored) {
                boolean grouped = s.score() >= th;
                if (grouped && s.pair().same()) tp++;
                else if (grouped) fp++;
                else if (s.pair().same()) fn++;
                else tn++;
            }
            rows.add(new Row(th, tp, fp, fn, tn));
        }
        return rows;
    }
}
