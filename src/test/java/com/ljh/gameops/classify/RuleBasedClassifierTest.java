// 규칙 분류기 — 키워드로 분류·심각도가 정해지는지
package com.ljh.gameops.classify;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedClassifierTest {
    private final RuleBasedClassifier c = new RuleBasedClassifier();

    @Test
    void 결제_문제는_가장_심각하다() {
        Classification r = c.classify("gemtower", "보석 패키지 결제했는데 보석이 안 들어왔어요");
        assertThat(r.category()).isEqualTo(Category.PAYMENT);
        assertThat(r.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void 튕김은_크래시로_분류한다() {
        Classification r = c.classify("gemtower", "5웨이브에서 자꾸 튕겨요");
        assertThat(r.category()).isEqualTo(Category.CRASH);
        assertThat(r.severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void 아무_규칙에도_안_걸리면_기타() {
        Classification r = c.classify("gemtower", "재밌어요 다음 업데이트 언제인가요");
        assertThat(r.category()).isEqualTo(Category.OTHER);
        assertThat(r.classifiedBy()).isEqualTo("rules");
    }

    @Test
    void 제목은_첫_줄을_60자로_자른다() {
        String longLine = "가".repeat(80);
        Classification r = c.classify("gemtower", longLine + "\n둘째 줄");
        assertThat(r.title()).hasSize(60).endsWith("…");
    }
}
