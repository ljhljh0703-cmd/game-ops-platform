// 평가 관문 — 분류 규칙을 고쳐 정확도가 기준 밑으로 떨어지면 테스트가 실패한다(규칙 고치기 고리의 안전장치)
package com.ljh.gameops.eval;

import com.ljh.gameops.classify.RuleBasedClassifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvalGateTest {
    // 2026-10-20 v3 규칙 결과에서 한두 건 여유를 둔 바닥값. 회귀 문장(한 번 틀렸던 문장)은 전부 맞아야 한다
    static final double DEV_FLOOR = 0.95;
    static final double HOLDOUT_FLOOR = 0.80;

    @Test
    void 기본_규칙의_분류_정확도가_바닥값_이상() throws Exception {
        var cases = EvalData.cases(EvalMain.CASES);
        var classifier = new RuleBasedClassifier();
        assertThat(ClassifierEvaluator.run(classifier, cases, "dev").categoryAccuracy()).isGreaterThanOrEqualTo(DEV_FLOOR);
        assertThat(ClassifierEvaluator.run(classifier, cases, "holdout").categoryAccuracy()).isGreaterThanOrEqualTo(HOLDOUT_FLOOR);
        var reg = ClassifierEvaluator.run(classifier, EvalData.cases(EvalMain.REGRESSION), "regression");
        assertThat(reg.misses()).as("회귀 문장이 다시 틀림").isEmpty();
    }

    @Test
    void 결제_함정은_결제로_잡지_않는다() throws Exception {
        var classifier = new RuleBasedClassifier();
        assertThat(classifier.classify("t", "결제 화면 글씨가 너무 작아요").category().name()).isNotEqualTo("PAYMENT");
        assertThat(classifier.classify("t", "하트 충전이 안 돼요").category().name()).isNotEqualTo("PAYMENT");
        assertThat(classifier.classify("t", "결제했는데 보석이 안 들어왔어요").category().name()).isEqualTo("PAYMENT");
        // 돈이 걸린 순간의 튕김은 결제로 본다(운영 설계서 3-1: 결제를 맨 위에 둔 이유)
        assertThat(classifier.classify("t", "결제하다 튕겼어요").category().name()).isEqualTo("PAYMENT");
    }

    @Test
    void 운영_규칙_파일과_기본_규칙이_같다() throws Exception {
        assertThat(Files.readString(Path.of("config/classifier-rules.json")))
                .isEqualTo(Files.readString(Path.of("src/main/resources/classifier-rules.json")));
    }

    @Test
    void 평가_묶음_모양() throws Exception {
        var cases = EvalData.cases(EvalMain.CASES);
        assertThat(cases).hasSize(90);
        assertThat(cases.stream().filter(c -> c.split().equals("holdout"))).hasSize(30);
        assertThat(EvalData.pairs(EvalMain.PAIRS)).hasSize(40);
    }

    @Test
    void 운영_묶기_기준에서_엉뚱하게_묶음_0건() throws Exception {
        var scored = DuplicateEvaluator.score(EvalData.pairs(EvalMain.PAIRS), EvalMain.CURRENT_METHOD, new RuleBasedClassifier());
        var row = DuplicateEvaluator.sweep(scored, EvalMain.CURRENT_THRESHOLD, EvalMain.CURRENT_THRESHOLD, 0.05).get(0);
        assertThat(row.fp()).as("다른 문제를 한 이슈로 묶음").isZero();
        // 2026-10-20 P9 반영 시점 45%. 규칙을 고쳐 분류가 흔들리면 묶기도 같이 떨어지므로 함께 지킨다
        assertThat(row.recall()).isGreaterThanOrEqualTo(0.40);
    }

    @Test
    void 평가_기준과_운영_설정이_같다() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("DUPLICATE_THRESHOLD:" + String.format(java.util.Locale.ROOT, "%.2f", EvalMain.CURRENT_THRESHOLD));
        assertThat(yml).contains("DUPLICATE_SAME_CATEGORY_ONLY:true");
    }
}
