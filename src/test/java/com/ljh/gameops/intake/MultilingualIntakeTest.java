// 외국어 제보(결정 42) — 언어를 저장하고, AI 번역을 함께 보관하고, 분류는 원문 규칙 먼저 · 제목은 번역으로
package com.ljh.gameops.intake;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.report.ReportRepository;
import com.ljh.gameops.report.SourceChannel;
import com.ljh.gameops.report.Translator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultilingualIntakeTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired IssueRepository issues;
    @Autowired ReportRepository reports;

    final List<String> translated = new ArrayList<>();

    IntakeService target() { return org.springframework.test.util.AopTestUtils.getTargetObject(intake); }

    void fakeTranslator(String ko) {
        target().translator = new Translator() {
            public String toKorean(String g, String t) { translated.add(t); return ko; }
            public boolean enabled() { return true; }
        };
    }

    @AfterEach
    void restore() { target().translator = Translator.NONE; }

    private IntakeResult submit(String body, String id) {
        return intake.submit(new SubmitReport("gemtower", SourceChannel.DISCORD, id, "u-" + id, body, null));
    }

    @Test
    void 영어_제보는_번역을_보관하고_원문_규칙으로_분류() {
        fakeTranslator("게임이 웨이브 5에 도달할 때마다 충돌합니다");
        IntakeResult r = submit("The game crashes every time I reach wave 5", "e1");
        var report = reports.findById(r.reportId()).orElseThrow();
        assertThat(report.getLanguage()).isEqualTo("en");
        assertThat(report.getTranslationKo()).isEqualTo("게임이 웨이브 5에 도달할 때마다 충돌합니다");
        assertThat(report.getBody()).isEqualTo("The game crashes every time I reach wave 5"); // 원문은 그대로
        var issue = issues.findById(r.issueId()).orElseThrow();
        assertThat(issue.getCategory()).isEqualTo(Category.CRASH); // 번역문(「충돌」)으로는 못 잡지만 원문 「crash」로
        assertThat(issue.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(issue.getTitle()).startsWith("게임이 웨이브 5"); // 운영자가 읽는 제목은 한국어
    }

    @Test
    void 원문_규칙이_안_걸리면_번역으로_분류() {
        fakeTranslator("결제했는데 보석이 안 들어왔어요");
        IntakeResult r = submit("Je n'ai pas reçu mes gemmes après l'achat", "f1");
        var issue = issues.findById(r.issueId()).orElseThrow();
        assertThat(issue.getCategory()).isEqualTo(Category.PAYMENT);
        assertThat(issue.getSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void 한국어_제보는_번역하지_않는다() {
        fakeTranslator("쓰이면 안 됨");
        IntakeResult r = submit("5웨이브에서 게임이 튕겨요", "k1");
        assertThat(translated).isEmpty();
        var report = reports.findById(r.reportId()).orElseThrow();
        assertThat(report.getLanguage()).isEqualTo("ko");
        assertThat(report.getTranslationKo()).isNull();
    }

    @Test
    void 번역이_실패해도_원문으로_접수() {
        fakeTranslator(null);
        IntakeResult r = submit("5ウェーブで毎回アプリが落ちます", "j1");
        assertThat(issues.findById(r.issueId()).orElseThrow().getCategory()).isEqualTo(Category.CRASH);
        assertThat(reports.findById(r.reportId()).orElseThrow().getLanguage()).isEqualTo("ja");
    }

    @Test
    void 슬랙_카드는_AI_번역을_먼저_원문을_아래에() {
        fakeTranslator("게임이 튕겨요");
        IntakeResult r = submit("The game crashes at wave 5", "s1");
        var rep = reports.findById(r.reportId()).orElseThrow();
        String q = com.ljh.gameops.notify.IssueCardFactoryAccess.quote(rep);
        assertThat(q).startsWith("🌐 영어 제보 · AI 번역: 게임이 튕겨요").contains("원문: The game crashes at wave 5");
    }
}
