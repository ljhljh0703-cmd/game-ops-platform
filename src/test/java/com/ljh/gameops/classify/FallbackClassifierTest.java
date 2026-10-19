// LLM이 실패하거나 엉뚱한 답을 내면 규칙 분류로 내려가는지
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FallbackClassifierTest {

    @Test
    void 주_분류기가_예외를_던지면_규칙_분류로_내려간다() {
        IssueClassifier broken = (g, b) -> { throw new ClassifierException("timeout"); };
        Classification r = new FallbackClassifier(broken, new RuleBasedClassifier()).classify("gemtower", "게임이 튕겨요");
        assertThat(r.classifiedBy()).isEqualTo("rules");
        assertThat(r.category()).isEqualTo(Category.CRASH);
    }

    @Test
    void 주_분류기가_성공하면_그_결과를_쓴다() {
        IssueClassifier ok = (g, b) -> new Classification(Category.BALANCE, Severity.LOW, "타워 밸런스", "llm");
        Classification r = new FallbackClassifier(ok, new RuleBasedClassifier()).classify("gemtower", "게임이 튕겨요");
        assertThat(r.classifiedBy()).isEqualTo("llm");
    }

    @Test
    void LLM_응답이_없는_분류값이면_예외로_처리된다() throws Exception {
        LlmClassifier llm = new LlmClassifier(null, "m", new ObjectMapper());
        var bad = new ObjectMapper().readTree("""
                {"choices":[{"message":{"content":"{\\"category\\":\\"WHATEVER\\",\\"severity\\":\\"HIGH\\",\\"title\\":\\"x\\"}"}}]}
                """);
        assertThatThrownBy(() -> llm.parse(bad)).isInstanceOf(ClassifierException.class);
    }

    @Test
    void LLM_응답의_JSON_앞뒤_잡문은_무시한다() throws Exception {
        LlmClassifier llm = new LlmClassifier(null, "m", new ObjectMapper());
        var good = new ObjectMapper().readTree("""
                {"choices":[{"message":{"content":"결과: {\\"category\\":\\"BUG\\",\\"severity\\":\\"MEDIUM\\",\\"title\\":\\"보석 합성 오류\\"} 끝"}}]}
                """);
        Classification r = llm.parse(good);
        assertThat(r.category()).isEqualTo(Category.BUG);
        assertThat(r.title()).isEqualTo("보석 합성 오류");
    }
}
