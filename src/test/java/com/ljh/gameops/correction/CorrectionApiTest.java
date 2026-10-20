// 자동 판단 되돌리기 — 떼어내기·분류 고치기·고친 기록·요약
package com.ljh.gameops.correction;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.intake.IntakeResult;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectionApiTest extends IntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;
    @Autowired IssueRepository issues;
    @Autowired CorrectionRepository corrections;

    // 「튕겨요」와 「느려요」는 글자가 0.6만큼 겹쳐 자동으로 한 이슈에 묶인다(기준 0.55). 사람이 보면 다른 문제다.
    static final String CRASH = "스테이지 3에서 게임이 튕겨요";
    static final String SLOW = "스테이지 3에서 게임이 느려요";

    private IntakeResult submit(String body) {
        return intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "p-" + body.hashCode(), body, null));
    }

    private HttpHeaders admin() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", "test-admin-key");
        return h;
    }

    private ResponseEntity<Map> detach(long issueId, long reportId, HttpHeaders h) {
        return http.exchange("/api/issues/" + issueId + "/reports/" + reportId + "/detach", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "튕김과 느림은 다른 문제"), h), Map.class);
    }

    private ResponseEntity<Map> reclassify(long issueId, Map<String, String> body) {
        return http.exchange("/api/issues/" + issueId + "/classification", HttpMethod.PATCH,
                new HttpEntity<>(body, admin()), Map.class);
    }

    @Test
    void 잘못_묶인_제보를_떼어내면_새_이슈가_되고_분류를_다시_한다() {
        IntakeResult a = submit(CRASH);
        IntakeResult b = submit(SLOW);
        assertThat(b.outcome()).isEqualTo(IntakeResult.Outcome.ATTACHED_TO_EXISTING);
        assertThat(b.issueId()).isEqualTo(a.issueId());

        ResponseEntity<Map> r = detach(a.issueId(), b.reportId(), admin());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> original = (Map<?, ?>) r.getBody().get("original");
        Map<?, ?> created = (Map<?, ?>) r.getBody().get("created");
        assertThat(original.get("reportCount")).isEqualTo(1);
        assertThat(original.get("category")).isEqualTo("CRASH");
        assertThat(created.get("category")).isEqualTo("PERFORMANCE");
        assertThat(created.get("severity")).isEqualTo("MEDIUM");
        assertThat(created.get("reportCount")).isEqualTo(1);

        long newId = ((Number) created.get("id")).longValue();
        assertThat(jdbc.queryForObject("select issue_id from issue_report where report_id = ?", Long.class, b.reportId()))
                .isEqualTo(newId);

        Correction c = corrections.findAll().get(0);
        assertThat(c.getKind()).isEqualTo(CorrectionKind.DETACH);
        assertThat(c.getSimilarity()).isBetween(0.55, 0.65);
        assertThat(c.getBeforeValue()).isEqualTo("CRASH/HIGH");
        assertThat(c.getAfterValue()).isEqualTo("PERFORMANCE/MEDIUM");
        assertThat(c.getActor()).isEqualTo("local-admin");
        assertThat(c.getReason()).isEqualTo("튕김과 느림은 다른 문제");

        String payload = jdbc.queryForObject(
                "select payload from notification_outbox where payload like '%detachedFrom%'", String.class);
        assertThat(payload).contains("\"issueId\":" + newId).contains("\"detachedFrom\":" + a.issueId());
    }

    @Test
    void 대표_제보를_떼어내면_남은_제보가_대표가_되고_이후_비교도_새_대표로_한다() {
        IntakeResult slow = submit(SLOW);          // 대표
        IntakeResult crash = submit(CRASH);        // 같은 이슈에 묶이며 심각도 HIGH로 오름
        assertThat(crash.issueId()).isEqualTo(slow.issueId());

        ResponseEntity<Map> r = detach(slow.issueId(), slow.reportId(), admin());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        var original = issues.findById(slow.issueId()).orElseThrow();
        assertThat(original.getRepresentativeReportId()).isEqualTo(crash.reportId());
        assertThat(original.getTitle()).isEqualTo(CRASH);
        assertThat(original.getSeverity().name()).isEqualTo("HIGH"); // 자동으로 내리지 않는다

        // 같은 「느려요」 제보가 또 오면 떼어낸 쪽(새 이슈)에 붙는다
        long createdId = ((Number) ((Map<?, ?>) r.getBody().get("created")).get("id")).longValue();
        assertThat(submit(SLOW).issueId()).isEqualTo(createdId);
    }

    @Test
    void 글이_똑같아도_사람이_떼어내면_따로_간다() {
        IntakeResult a = submit("버튼이 안 눌려요");
        IntakeResult b = submit("버튼이 안 눌려요!!");
        assertThat(b.issueId()).isEqualTo(a.issueId());
        ResponseEntity<Map> r = detach(a.issueId(), b.reportId(), admin());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select count(*) from issue where status = 'OPEN'", Long.class)).isEqualTo(2);
    }

    @Test
    void 제보가_하나뿐인_이슈에서는_떼어낼_수_없다_409() {
        IntakeResult a = submit(CRASH);
        assertThat(detach(a.issueId(), a.reportId(), admin()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(corrections.count()).isZero();
    }

    @Test
    void 다른_이슈의_제보를_지정하면_404() {
        IntakeResult a = submit(CRASH);
        submit(SLOW);
        IntakeResult other = submit("결제했는데 젤리가 안 들어와요");
        assertThat(detach(a.issueId(), other.reportId(), admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 관리자_키_없이는_고칠_수_없다() {
        IntakeResult a = submit(CRASH);
        IntakeResult b = submit(SLOW);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(detach(a.issueId(), b.reportId(), h).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> r = http.exchange("/api/issues/" + a.issueId() + "/classification", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("severity", "LOW"), h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/api/corrections?game=jelly-panic", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 사람은_심각도를_내릴_수_있고_기록이_남는다() {
        IntakeResult a = submit("버튼 색이 이상해요"); // 「이상해」 키워드로 BUG/MEDIUM 오분류(디자인 의견)
        ResponseEntity<Map> r = reclassify(a.issueId(), Map.of("category", "UX", "severity", "LOW", "reason", "버그 아님, 디자인 의견"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("category")).isEqualTo("UX");
        assertThat(r.getBody().get("severity")).isEqualTo("LOW");
        assertThat(r.getBody().get("classifiedBy")).isEqualTo("human");

        Correction c = corrections.findAll().get(0);
        assertThat(c.getKind()).isEqualTo(CorrectionKind.RECLASSIFY);
        assertThat(c.getBeforeValue()).isEqualTo("BUG/MEDIUM");
        assertThat(c.getAfterValue()).isEqualTo("UX/LOW");
        assertThat(c.getAutoBy()).isEqualTo("rules");
    }

    @Test
    void 사람이_심각도를_올리면_긴급_알림이_다시_나간다() {
        IntakeResult a = submit("젤리 색이 이상해요");
        long before = jdbc.queryForObject("select count(*) from notification_outbox where type = 'SEVERITY_RAISED'", Long.class);
        reclassify(a.issueId(), Map.of("severity", "CRITICAL"));
        long after = jdbc.queryForObject("select count(*) from notification_outbox where type = 'SEVERITY_RAISED'", Long.class);
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void 바뀌는_게_없으면_기록하지_않고_둘_다_비면_400() {
        IntakeResult a = submit(CRASH);
        assertThat(reclassify(a.issueId(), Map.of("category", "CRASH", "severity", "HIGH")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(corrections.count()).isZero();
        assertThat(reclassify(a.issueId(), Map.of("reason", "x")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 요약은_자주_틀리는_방향과_떼어낸_유사도를_보여준다() {
        IntakeResult a = submit(CRASH);
        IntakeResult b = submit(SLOW);
        detach(a.issueId(), b.reportId(), admin());
        reclassify(submit("버튼 색이 이상해요").issueId(), Map.of("category", "UX", "severity", "LOW"));
        reclassify(submit("메뉴 글자 배치가 이상해요").issueId(), Map.of("category", "UX", "severity", "LOW"));

        ResponseEntity<Map> r = http.exchange("/api/corrections/summary?game=jelly-panic", HttpMethod.GET,
                new HttpEntity<>(admin()), Map.class);
        Map<?, ?> s = r.getBody();
        assertThat(s.get("total")).isEqualTo(3);
        assertThat(s.get("severityLowered")).isEqualTo(2);
        List<?> fixes = (List<?>) s.get("categoryFixes");
        assertThat((Map<String, Object>) fixes.get(0)).containsEntry("from", "BUG").containsEntry("to", "UX").containsEntry("count", 2);
        Map<?, ?> d = (Map<?, ?>) s.get("detaches");
        assertThat(d.get("count")).isEqualTo(1);
        assertThat(((Number) d.get("avgSimilarity")).doubleValue()).isBetween(0.55, 0.65);

        ResponseEntity<List> list = http.exchange("/api/corrections?game=jelly-panic", HttpMethod.GET,
                new HttpEntity<>(admin()), List.class);
        assertThat(list.getBody()).hasSize(3);
    }

    @Test
    void 사람이_내린_심각도는_비슷한_제보가_더_와도_자동으로_다시_오르지_않는다() {
        IntakeResult a = submit("상점에서 결제 버튼 위치가 헷갈려요");
        reclassify(a.issueId(), Map.of("category", "UX", "severity", "LOW"));
        long raisedBefore = jdbc.queryForObject("select count(*) from notification_outbox where type = 'SEVERITY_RAISED'", Long.class);
        IntakeResult b = submit("상점에서 결제 버튼 위치가 너무 헷갈려요");
        assertThat(b.issueId()).isEqualTo(a.issueId());
        var issue = issues.findById(a.issueId()).orElseThrow();
        assertThat(issue.getSeverity().name()).isEqualTo("LOW");
        assertThat(issue.getReportCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where type = 'SEVERITY_RAISED'", Long.class))
                .isEqualTo(raisedBefore);
    }

    @Test
    void 닫힌_이슈에서는_떼어낼_수_없고_분류를_고쳐도_긴급_알림은_없다() {
        IntakeResult a = submit(CRASH);
        IntakeResult b = submit(SLOW);
        jdbc.update("update issue set status = 'RESOLVED', resolved_at = now() where id = ?", a.issueId());
        assertThat(detach(a.issueId(), b.reportId(), admin()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        IntakeResult c = submit("젤리 색이 이상해요");
        jdbc.update("update issue set status = 'RESOLVED', resolved_at = now() where id = ?", c.issueId());
        long before = jdbc.queryForObject("select count(*) from notification_outbox where type = 'SEVERITY_RAISED'", Long.class);
        assertThat(reclassify(c.issueId(), Map.of("severity", "CRITICAL")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where type = 'SEVERITY_RAISED'", Long.class))
                .isEqualTo(before);
    }
}
