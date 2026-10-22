// 묶기 둘째 단계(결정 41) — 글자 비교로 못 묶은 제보만 AI 에게 묻고, AI 가 같다고 하면 붙인다. 실패·낮은 확신이면 새 이슈
package com.ljh.gameops.intake;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiDedupeTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired IssueRepository issues;

    final List<String[]> asked = new ArrayList<>();
    double answer;

    DuplicateJudge fake() {
        return new DuplicateJudge() {
            public double confidence(String g, String a, String b) { asked.add(new String[]{a, b}); return answer; }
            public boolean enabled() { return true; }
        };
    }

    /** IntakeService 는 거래 프록시라 필드는 실제 객체에 넣는다 */
    IntakeService target() { return org.springframework.test.util.AopTestUtils.getTargetObject(intake); }

    @AfterEach
    void restore() { target().judge = DuplicateJudge.NONE; }

    private IntakeResult submit(String body, String id) {
        return intake.submit(new SubmitReport("gemtower", SourceChannel.DISCORD, id, "u-" + id, body, null));
    }

    @Test
    void 말이_달라도_AI가_같다고_하면_한_이슈로() {
        target().judge = fake();
        answer = 0.9;
        IntakeResult a = submit("5웨이브에서 게임이 튕겨요", "a1");
        IntakeResult b = submit("다섯번째 판 시작하면 앱이 꺼짐", "a2");
        assertThat(b.outcome()).isEqualTo(IntakeResult.Outcome.ATTACHED_TO_EXISTING);
        assertThat(b.issueId()).isEqualTo(a.issueId());
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)[1]).isEqualTo("5웨이브에서 게임이 튕겨요"); // 이슈의 대표 제보 원문과 비교
    }

    @Test
    void 확신이_낮으면_새_이슈() {
        target().judge = fake();
        answer = 0.2;
        IntakeResult a = submit("5웨이브에서 게임이 튕겨요", "b1");
        IntakeResult b = submit("다섯번째 판 시작하면 앱이 꺼짐", "b2");
        assertThat(b.outcome()).isEqualTo(IntakeResult.Outcome.NEW_ISSUE);
        assertThat(b.issueId()).isNotEqualTo(a.issueId());
    }

    @Test
    void 글자_비교로_붙는_제보는_AI에게_묻지_않는다() {
        target().judge = fake();
        answer = 0.9;
        submit("5웨이브에서 게임이 튕겨요", "c1");
        IntakeResult b = submit("5 웨이브에서 게임이 튕겨요ㅠㅠ", "c2");
        assertThat(b.outcome()).isEqualTo(IntakeResult.Outcome.ATTACHED_TO_EXISTING);
        assertThat(asked).isEmpty();
    }

    @Test
    void 분류가_다른_이슈는_후보로_묻지_않는다() {
        target().judge = fake();
        answer = 0.9;
        submit("루비 타워 공격력이 너무 약해요", "d1"); // 밸런스
        IntakeResult b = submit("5웨이브에서 게임이 튕겨요", "d2"); // 튕김
        assertThat(b.outcome()).isEqualTo(IntakeResult.Outcome.NEW_ISSUE);
        assertThat(asked).isEmpty();
    }
}
