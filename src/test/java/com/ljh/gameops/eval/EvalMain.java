// 평가 실행 — ./gradlew evalClassifier [-Prules=규칙파일] [-Pllm=true] [-Plabel=이름]
// 결과를 eval/results/<날짜>-<이름>.md 로 남기고 요약을 화면에 찍는다. LLM 평가는 LLM_API_KEY 가 있을 때만.
package com.ljh.gameops.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.classify.*;
import com.ljh.gameops.issue.Category;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public final class EvalMain {
    public static final Path CASES = Path.of("eval/classifier-cases.jsonl");
    public static final Path PAIRS = Path.of("eval/duplicate-pairs.jsonl");
    /** 고정 회귀 문장 — 한 번 틀렸던 문장. 규칙을 고칠 때 봐도 된다 */
    public static final Path REGRESSION = Path.of("eval/classifier-regression.jsonl");
    public static final double CURRENT_THRESHOLD = 0.55;

    public static void main(String[] args) throws Exception {
        String rules = System.getProperty("eval.rules", "");
        boolean llm = Boolean.getBoolean("eval.llm");
        // holdout 은 규칙 후보를 고르는 데 쓰면 dev 가 되어 버린다. 릴리스 때 한 번만 -Pholdout=true 로 본다
        boolean showHoldout = Boolean.getBoolean("eval.holdout");
        String label = System.getProperty("eval.label", llm ? "llm" : (rules.isBlank() ? "rules" : "rules-candidate"));

        IssueClassifier classifier = classifier(rules, llm);
        var cases = EvalData.cases(CASES);
        var dev = ClassifierEvaluator.run(classifier, cases, "dev");
        var hold = showHoldout ? ClassifierEvaluator.run(classifier, cases, "holdout") : null;
        var reg = ClassifierEvaluator.run(classifier, EvalData.cases(REGRESSION), "regression");
        var pairs = EvalData.pairs(PAIRS);
        var scored = DuplicateEvaluator.score(pairs);
        var sweep = DuplicateEvaluator.sweep(scored, 0.10, 0.80, 0.05);

        String md = Report.markdown(label, rules.isBlank() ? "기본 규칙(classifier-rules.json)" : rules, dev, hold, reg, scored, sweep)
                + Report.methods(pairs, classifier);
        Path out = Path.of("eval/results/" + LocalDate.now() + "-" + label + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "[%s] 분류 정확도 dev %.1f%% · 회귀 %d/%d%s | 분류+심각도 dev %.1f%% → %s%n",
                label, dev.categoryAccuracy() * 100, reg.bothOk(), reg.total(),
                hold == null ? "" : String.format(Locale.ROOT, " · holdout %.1f%%", hold.categoryAccuracy() * 100),
                dev.bothAccuracy() * 100, out);
    }

    static IssueClassifier classifier(String rulesFile, boolean llm) throws Exception {
        RuleSource source = new BundledRuleSource();
        if (!rulesFile.isBlank()) {
            var parsed = RuleParser.parse(Files.readString(Path.of(rulesFile), StandardCharsets.UTF_8));
            source = () -> new RuleSet(parsed, rulesFile, null, null);
        }
        RuleBasedClassifier rules = new RuleBasedClassifier(source);
        if (!llm) return rules;
        String key = System.getenv("LLM_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("LLM 평가는 LLM_API_KEY 가 있어야 합니다");
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        rf.setReadTimeout(Duration.ofSeconds(20));
        RestClient client = RestClient.builder()
                .baseUrl(System.getenv().getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"))
                .requestFactory(rf).defaultHeader("Authorization", "Bearer " + key).build();
        return new FallbackClassifier(new LlmClassifier(client, System.getenv().getOrDefault("LLM_MODEL", "gpt-4o-mini"),
                new ObjectMapper()), rules);
    }

    /** 결과 문서 */
    static final class Report {
        static String markdown(String label, String rulesSource, ClassifierEvaluator.Result dev, ClassifierEvaluator.Result hold,
                               ClassifierEvaluator.Result reg, List<DuplicateEvaluator.Scored> scored, List<DuplicateEvaluator.Row> sweep) {
            StringBuilder s = new StringBuilder();
            s.append("# 평가 결과 — ").append(label).append(" (").append(LocalDate.now()).append(")\n\n");
            s.append("규칙: ").append(rulesSource).append("\n\n");
            s.append("## 분류\n\n| 묶음 | 건수 | 분류 정확도 | 심각도 정확도 | 둘 다 |\n|---|---|---|---|---|\n");
            var shown = new java.util.ArrayList<ClassifierEvaluator.Result>(List.of(dev, reg));
            if (hold != null) shown.add(hold);
            for (var r : shown) {
                s.append(String.format(Locale.ROOT, "| %s | %d | %.1f%% | %.1f%% | %.1f%% |%n", r.split(), r.total(),
                        r.categoryAccuracy() * 100, r.severityAccuracy() * 100, r.bothAccuracy() * 100));
            }
            s.append("\n### 분류별 (dev)\n\n| 분류 | 정밀도 | 재현율 |\n|---|---|---|\n");
            for (Category c : Category.values()) {
                s.append(String.format(Locale.ROOT, "| %s | %s | %s |%n", c, pct(dev.precision(c)), pct(dev.recall(c))));
            }
            s.append("\n### 헷갈린 표 (dev, 행 = 정답, 열 = 판단)\n\n| 정답＼판단 |");
            for (Category c : Category.values()) s.append(' ').append(c).append(" |");
            s.append("\n|---|").append("---|".repeat(Category.values().length)).append('\n');
            for (Category a : Category.values()) {
                s.append("| ").append(a).append(" |");
                for (Category b : Category.values()) {
                    int n = dev.confusion().get(a).getOrDefault(b, 0);
                    s.append(' ').append(n == 0 ? "·" : (a == b ? String.valueOf(n) : "**" + n + "**")).append(" |");
                }
                s.append('\n');
            }
            s.append("\n### 틀린 사례 (dev)\n\n| # | 글 | 정답 | 판단 | 메모 |\n|---|---|---|---|---|\n");
            for (var m : dev.misses()) {
                s.append(String.format("| %d | %s | %s/%s | %s/%s | %s |%n", m.c().id(), m.c().body(), m.c().category(), m.c().severity(),
                        m.got().category(), m.got().severity(), m.c().note() == null ? "" : m.c().note()));
            }
            for (var m : reg.misses()) {
                s.append(String.format("| %d | %s | %s/%s | %s/%s | 회귀 |%n", m.c().id(), m.c().body(), m.c().category(), m.c().severity(),
                        m.got().category(), m.got().severity()));
            }
            s.append(hold == null ? "\nholdout 은 이번에 재지 않았다(-Pholdout=true 는 릴리스 때 한 번만).\n"
                    : "\nholdout 틀린 사례는 일부러 적지 않는다(규칙을 holdout 에 맞추지 않기 위해). 건수만 본다.\n");

            s.append("\n## 묶기 기준값\n\n같은 문제 20쌍 · 다른 문제 20쌍. 현재 기준 ").append(CURRENT_THRESHOLD).append(".\n\n");
            s.append("| 기준 | 묶음 정밀도 | 묶음 재현율 | F1 | 엉뚱하게 묶음 | 못 묶음 |\n|---|---|---|---|---|---|\n");
            for (var r : sweep) {
                boolean now = Math.abs(r.threshold() - CURRENT_THRESHOLD) < 1e-9;
                s.append(String.format(Locale.ROOT, "| %s%.2f%s | %s | %s | %.2f | %d | %d |%n", now ? "**" : "", r.threshold(), now ? "**" : "",
                        pct(r.precision()), pct(r.recall()), r.f1(), r.fp(), r.fn()));
            }
            s.append("\n### 쌍별 점수\n\n| # | 가 | 나 | 같은 문제 | 유사도 |\n|---|---|---|---|---|\n");
            for (var sc : scored) {
                s.append(String.format(Locale.ROOT, "| %d | %s | %s | %s | %.2f |%n", sc.pair().id(), sc.pair().a(), sc.pair().b(),
                        sc.pair().same() ? "예" : "아니오", sc.score()));
            }
            return s.toString();
        }

        /** 유사도 방식 후보별 가장 좋은 기준값 — 운영 방식을 바꿀지 판단 근거 */
        static String methods(List<EvalData.Pair> pairs, IssueClassifier classifier) {
            StringBuilder s = new StringBuilder("\n## 유사도 방식 비교 (후보)\n\n| 방식 | 가장 좋은 기준 | 정밀도 | 재현율 | F1 | 엉뚱하게 묶음 |\n|---|---|---|---|---|---|\n");
            for (var m : DuplicateEvaluator.Method.values()) {
                var best = DuplicateEvaluator.sweep(DuplicateEvaluator.score(pairs, m, classifier), 0.10, 0.95, 0.05).stream()
                        .max(java.util.Comparator.comparingDouble(DuplicateEvaluator.Row::f1)
                                .thenComparingDouble(DuplicateEvaluator.Row::threshold)).orElseThrow();
                s.append(String.format(Locale.ROOT, "| %s | %.2f | %s | %s | %.2f | %d |%n", m.label, best.threshold(),
                        pct(best.precision()), pct(best.recall()), best.f1(), best.fp()));
            }
            s.append("\n### 엉뚱하게 묶음 0건을 지키면서 가장 많이 묶는 기준\n\n잘못 묶이면 다른 문제가 한 이슈에 숨는다. 그래서 운영은 F1 보다 이 표를 먼저 본다.\n\n| 방식 | 기준 | 재현율 |\n|---|---|---|\n");
            for (var m : DuplicateEvaluator.Method.values()) {
                var safe = DuplicateEvaluator.sweep(DuplicateEvaluator.score(pairs, m, classifier), 0.10, 0.95, 0.05).stream()
                        .filter(r -> r.fp() == 0).max(java.util.Comparator.comparingDouble(DuplicateEvaluator.Row::recall)
                                .thenComparingDouble(r -> -r.threshold())).orElse(null);
                s.append(safe == null ? "| " + m.label + " | — | — |\n"
                        : String.format(Locale.ROOT, "| %s | %.2f | %s |%n", m.label, safe.threshold(), pct(safe.recall())));
            }
            s.append("\n분류가 같을 때만 묶는 방식은 이 평가에 쓴 분류 규칙을 그대로 쓴다.\n");
            return s.toString();
        }

        private static String pct(double v) {
            return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.0f%%", v * 100);
        }
    }
}
