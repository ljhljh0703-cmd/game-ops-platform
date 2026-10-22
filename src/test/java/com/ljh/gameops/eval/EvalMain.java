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
    /** 외국어(영어·일본어) 제보와 언어가 섞인 묶기 쌍(결정 42) */
    public static final Path MULTI = Path.of("eval/classifier-multilingual.jsonl");
    public static final Path MULTI_PAIRS = Path.of("eval/duplicate-pairs-multilingual.jsonl");
    /** 운영 묶기 방식과 기준(application.yml 과 같아야 한다 — EvalGateTest 가 확인) */
    public static final double CURRENT_THRESHOLD = 0.20;
    public static final DuplicateEvaluator.Method CURRENT_METHOD = DuplicateEvaluator.Method.JACCARD_SAME_CATEGORY;

    public static void main(String[] args) throws Exception {
        String rules = System.getProperty("eval.rules", "");
        boolean llm = Boolean.getBoolean("eval.llm");
        // holdout 은 규칙 후보를 고르는 데 쓰면 dev 가 되어 버린다. 릴리스 때 한 번만 -Pholdout=true 로 본다
        boolean showHoldout = Boolean.getBoolean("eval.holdout");
        String label = System.getProperty("eval.label", llm ? "llm" : (rules.isBlank() ? "rules" : "rules-candidate"));

        IssueClassifier classifier = classifier(rules, llm);
        if (llm) classifier = new Counting(classifier);
        var cases = EvalData.cases(CASES);
        var dev = ClassifierEvaluator.run(classifier, cases, "dev");
        var hold = showHoldout ? ClassifierEvaluator.run(classifier, cases, "holdout") : null;
        var reg = ClassifierEvaluator.run(classifier, EvalData.cases(REGRESSION), "regression");
        var pairs = EvalData.pairs(PAIRS);
        if (METER != null) METER.purpose("pairs-classify");
        classifier = new Memo(classifier); // 묶기 평가는 같은 글을 방식마다 다시 분류한다 → 한 번만 부르게
        var scored = DuplicateEvaluator.score(pairs, CURRENT_METHOD, classifier);
        var sweep = DuplicateEvaluator.sweep(scored, 0.10, 0.80, 0.05);

        String md = Report.markdown(label, rules.isBlank() ? "기본 규칙(classifier-rules.json)" : rules, dev, hold, reg, scored, sweep)
                + Report.methods(pairs, classifier) + multilingual(rules, showHoldout)
                + (llm ? llmSection(pairs, (Counting) ((Memo) classifier).inner) : "");
        Path out = Path.of("eval/results/" + LocalDate.now() + "-" + label + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "[%s] 분류 정확도 dev %.1f%% · 회귀 %d/%d%s | 분류+심각도 dev %.1f%% → %s%n",
                label, dev.categoryAccuracy() * 100, reg.bothOk(), reg.total(),
                hold == null ? "" : String.format(Locale.ROOT, " · holdout %.1f%%", hold.categoryAccuracy() * 100),
                dev.bothAccuracy() * 100, out);
    }

    /** LLM 평가일 때만 채운다 */
    static LlmMeter METER;
    static RestClient CLIENT;
    static String MODEL;
    static boolean ANTHROPIC;

    /** 분류기가 LLM 대신 규칙으로 내려간 횟수(응답 형식 오류·호출 실패) */
    static final class Counting implements IssueClassifier {
        final IssueClassifier inner; int calls, fallbacks;
        Counting(IssueClassifier inner) { this.inner = inner; }
        public Classification classify(String g, String b) {
            Classification c = inner.classify(g, b);
            calls++;
            if (!"llm".equals(c.classifiedBy())) fallbacks++;
            return c;
        }
    }

    static final class Memo implements IssueClassifier {
        final IssueClassifier inner; final java.util.Map<String, Classification> seen = new java.util.HashMap<>();
        Memo(IssueClassifier inner) { this.inner = inner; }
        public Classification classify(String g, String b) { return seen.computeIfAbsent(g + "|" + b, k -> inner.classify(g, b)); }
    }

    /** 외국어 제보: 규칙이 원문 그대로 · (AI 키가 있으면) AI 번역 뒤 규칙. 운영과 같은 길(분류는 규칙, 번역은 AI) */
    static String multilingual(String rulesFile, boolean showHoldout) throws Exception {
        IssueClassifier rules = classifier(rulesFile, false);
        var ml = EvalData.cases(MULTI);
        StringBuilder s = new StringBuilder("\n## 외국어 제보 (영어·일본어 24건)\n\n| 방법 | 묶음 | 건수 | 분류 정확도 | 심각도 정확도 | 둘 다 |\n|---|---|---|---|---|---|\n");
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        IssueClassifier translated = null, combo = null;
        java.util.Map<String, String> koCache = new java.util.HashMap<>();
        java.util.function.Function<String, String> ko = null;
        if (METER != null) {
            var tr = new com.ljh.gameops.report.Translator.Llm(CLIENT, MODEL, ANTHROPIC, null);
            ko = b -> koCache.computeIfAbsent(b, k -> { METER.purpose("translate"); String t = tr.toKorean("eval", k); return t == null ? k : t; });
            var koF = ko;
            translated = (g, b) -> rules.classify(g, koF.apply(b));
            // 운영과 같은 길: 원문 규칙이 먼저, 아무 규칙도 안 걸리면(기타·낮음) 번역으로(IntakeService.classify)
            combo = (g, b) -> com.ljh.gameops.intake.IntakeService.classifyWithTranslation(rules, g, b, koF.apply(b));
        }
        for (String split : showHoldout ? List.of("dev", "holdout") : List.of("dev")) {
            var r = ClassifierEvaluator.run(rules, ml, split);
            s.append(String.format(Locale.ROOT, "| 규칙(원문) | %s | %d | %.1f%% | %.1f%% | %.1f%% |%n", split, r.total(),
                    r.categoryAccuracy() * 100, r.severityAccuracy() * 100, r.bothAccuracy() * 100));
            if (translated != null) {
                var t = ClassifierEvaluator.run(translated, ml, split);
                s.append(String.format(Locale.ROOT, "| AI 번역만 → 규칙 | %s | %d | %.1f%% | %.1f%% | %.1f%% |%n", split, t.total(),
                        t.categoryAccuracy() * 100, t.severityAccuracy() * 100, t.bothAccuracy() * 100));
                var c = ClassifierEvaluator.run(combo, ml, split);
                s.append(String.format(Locale.ROOT, "| 원문 규칙, 안 걸리면 번역 (운영) | %s | %d | %.1f%% | %.1f%% | %.1f%% |%n", split, c.total(),
                        c.categoryAccuracy() * 100, c.severityAccuracy() * 100, c.bothAccuracy() * 100));
                if (split.equals("dev")) for (var m : t.misses()) rows.add(new String[]{m.c().body(), koF(ko, m.c().body()),
                        m.c().category() + "/" + m.c().severity(), m.got().category() + "/" + m.got().severity()});
            }
        }
        var dev = ClassifierEvaluator.run(rules, ml, "dev");
        if (!dev.misses().isEmpty()) {
            s.append("\n규칙(원문) dev 틀린 사례\n\n| # | 글 | 정답 | 판단 |\n|---|---|---|---|\n");
            for (var m : dev.misses()) s.append(String.format("| %d | %s | %s/%s | %s/%s |%n", m.c().id(), m.c().body(),
                    m.c().category(), m.c().severity(), m.got().category(), m.got().severity()));
        }
        if (!rows.isEmpty()) {
            s.append("\nAI 번역만 쓸 때 dev 에서 틀린 것(번역에 한국어 규칙 낱말이 없어서)\n\n| 원문 | 번역 | 정답 | 판단 |\n|---|---|---|---|\n");
            for (String[] r : rows) s.append("| ").append(String.join(" | ", r)).append(" |\n");
        }
        var mp = EvalData.pairs(MULTI_PAIRS);
        var jac = DuplicateEvaluator.sweep(DuplicateEvaluator.score(mp, CURRENT_METHOD, rules), CURRENT_THRESHOLD, CURRENT_THRESHOLD, 0.05).get(0);
        s.append(String.format(Locale.ROOT, "%n언어가 섞인 묶기 %d쌍(같은 문제 %d): 운영 글자 비교(원문끼리) 재현율 %s · 엉뚱하게 묶음 %d%n",
                mp.size(), mp.stream().filter(EvalData.Pair::same).count(), Report.pct(jac.recall()), jac.fp()));
        if (METER != null) {
            METER.purpose("judge-multi");
            var j = DuplicateEvaluator.sweep(new LlmJudge(CLIENT, MODEL, ANTHROPIC).score(mp), 0.5, 0.5, 0.1).get(0);
            s.append(String.format(Locale.ROOT, "AI 판정(확신 0.5) 재현율 %s · 엉뚱하게 묶음 %d%n", Report.pct(j.recall()), j.fp()));
            var koF = ko;
            var tp = mp.stream().map(p -> new EvalData.Pair(p.id(), p.a(), koF.apply(p.b()), p.same())).toList();
            var jt = DuplicateEvaluator.sweep(DuplicateEvaluator.score(tp, CURRENT_METHOD, rules), CURRENT_THRESHOLD, CURRENT_THRESHOLD, 0.05).get(0);
            s.append(String.format(Locale.ROOT, "글자 비교를 번역문으로 해도 재현율 %s · 엉뚱하게 묶음 %d%n", Report.pct(jt.recall()), jt.fp()));
        }
        return s.toString();
    }

    private static String koF(java.util.function.Function<String, String> ko, String b) { return ko.apply(b).replace('|', '/'); }

    static String llmSection(List<EvalData.Pair> pairs, Counting counted) {
        METER.purpose("judge");
        var judged = new LlmJudge(CLIENT, MODEL, ANTHROPIC).score(pairs);
        var sweep = DuplicateEvaluator.sweep(judged, 0.10, 0.90, 0.10);
        StringBuilder s = new StringBuilder("\n## 속도·비용 (모델 " + MODEL + ")\n\n");
        s.append("단가는 운영 설정과 같게 입력 $").append(LlmMeter.PRICE_IN).append(" · 출력 $").append(LlmMeter.PRICE_OUT)
                .append(" (백만 토큰당). 시간은 이 평가를 돌린 곳에서 API 까지 왕복이다.\n\n");
        s.append("| 용도 | 호출 | 실패 | 중앙값 | 95% | 평균 토큰(입력/출력) | 한 번 | 1,000건 |\n|---|---|---|---|---|---|---|---|\n");
        s.append(METER.row("분류(dev·회귀·holdout)", "classify")).append(METER.row("분류(묶기 쌍 글)", "pairs-classify"))
                .append(METER.row("같은 문제 판정", "judge")).append(METER.row("번역(외국어 제보)", "translate"));
        s.append(String.format(Locale.ROOT, "%n분류 %d번 중 규칙으로 내려간 것 %d번(응답 형식 오류·호출 실패). 규칙 분류는 같은 기계에서 한 번 1ms 미만, 비용 0.%n",
                counted.calls, counted.fallbacks));
        s.append("\n## LLM 이 같은 문제인지 판정 (후보)\n\n같은 묶음 40쌍. 점수 = 「같다」 확신. 운영 자카드와 같은 표로 본다.\n\n");
        s.append("| 기준 | 정밀도 | 재현율 | F1 | 엉뚱하게 묶음 | 못 묶음 |\n|---|---|---|---|---|---|\n");
        for (var r : sweep) s.append(String.format(Locale.ROOT, "| %.1f | %s | %s | %.2f | %d | %d |%n", r.threshold(),
                Report.pct(r.precision()), Report.pct(r.recall()), r.f1(), r.fp(), r.fn()));
        s.append("\n### 판정 쌍별\n\n| # | 같은 문제 | LLM 확신 |\n|---|---|---|\n");
        for (var sc : judged) s.append(String.format(Locale.ROOT, "| %d | %s | %.2f |%n", sc.pair().id(), sc.pair().same() ? "예" : "아니오", sc.score()));
        return s.toString();
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
        METER = new LlmMeter();
        ANTHROPIC = com.ljh.gameops.ai.LlmRequest.isAnthropic(System.getenv("LLM_BASE_URL"));
        MODEL = System.getenv().getOrDefault("LLM_MODEL", "gpt-4o-mini");
        CLIENT = RestClient.builder()
                .baseUrl(System.getenv().getOrDefault("LLM_BASE_URL", "https://api.openai.com/v1"))
                .requestFactory(new org.springframework.http.client.BufferingClientHttpRequestFactory(rf))
                .requestInterceptor(METER.interceptor())
                .defaultHeader("Authorization", "Bearer " + key).build();
        return new FallbackClassifier(new LlmClassifier(CLIENT, MODEL, new ObjectMapper(), null, ANTHROPIC), rules);
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

            s.append("\n## 묶기 기준값\n\n같은 문제 20쌍 · 다른 문제 20쌍. 운영 방식 = ").append(CURRENT_METHOD.label)
                    .append(", 현재 기준 ").append(CURRENT_THRESHOLD).append(". 아래 유사도는 분류가 다르면 0.\n\n");
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

        static String pct(double v) {
            return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.0f%%", v * 100);
        }
    }
}
