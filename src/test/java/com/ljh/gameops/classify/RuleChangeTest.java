// 운영 판단 자동 고치기 — 시험을 통과한 수정안만 바로 반영하고 슬랙 카드를 보내며, 결제·크래시를 빼거나 낮추는 수정은 막고, 되돌릴 수 있다
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleChangeTest extends IntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired RuleBasedClassifier classifier;
    @Autowired ManagedRuleSource source;
    @Autowired OutboxRepository outbox;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE classifier_rule_version RESTART IDENTITY");
        source.invalidate();
    }

    private List<ClassificationRule> current() { return new ArrayList<>(classifier.rules().rules()); }

    /** 기존 규칙 i번째에 키워드를 더한 수정안 */
    private List<ClassificationRule> withKeyword(int i, String kw) {
        List<ClassificationRule> rs = current();
        var r = rs.get(i);
        List<String> ks = new ArrayList<>(r.keywords());
        ks.add(kw);
        rs.set(i, new ClassificationRule(r.category(), r.severity(), ks));
        return rs;
    }

    private int index(Category c) {
        var rs = current();
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).category() == c) return i;
        throw new IllegalStateException();
    }

    private ResponseEntity<Map> propose(List<ClassificationRule> rules, String key) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", key);
        String body = mapper.writeValueAsString(Map.of("rules", Map.of("rules", rules), "reason", "고친 기록: 버그→불편 3건"));
        return http.exchange("/api/admin/classifier/rules/proposals", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void 시험을_통과한_수정안은_바로_반영되고_슬랙_카드가_대기열에_들어간다() throws Exception {
        assertThat(classifier.classify("g", "아이콘 글자가 겹쳐 보여요").category()).isNotEqualTo(Category.UX);
        var r = propose(withKeyword(index(Category.UX), "글자가 겹쳐"), "test-agent-key");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("applied", true);
        assertThat((List<String>) r.getBody().get("changes")).singleElement().asString().contains("불편/낮음 + 「글자가 겹쳐」");
        assertThat(classifier.classify("g", "아이콘 글자가 겹쳐 보여요").category()).isEqualTo(Category.UX);
        assertThat(classifier.rules().source()).startsWith("AI 수정 #");
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.RULES_CHANGED)).hasSize(1);
        assertThat(jdbc.queryForObject("select actor from classifier_rule_version", String.class)).isEqualTo("ai-agent");
    }

    @Test
    void 결제_키워드를_빼는_수정은_막는다() throws Exception {
        List<ClassificationRule> rs = current();
        int p = index(Category.PAYMENT);
        var pr = rs.get(p);
        rs.set(p, new ClassificationRule(pr.category(), pr.severity(), pr.keywords().subList(1, pr.keywords().size())));
        var r = propose(rs, "test-agent-key");
        assertThat(r.getBody()).containsEntry("applied", false);
        assertThat(r.getBody().get("rejected").toString()).contains("결제 키워드를 빼는 수정은 사람이 파일로");
        assertThat(classifier.rules().source()).doesNotStartWith("AI 수정");
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.RULES_CHANGED)).isEmpty();
    }

    @Test
    void 크래시_심각도를_낮추는_수정과_시험_미달_수정은_막는다() throws Exception {
        List<ClassificationRule> rs = current();
        int c = index(Category.CRASH);
        rs.set(c, new ClassificationRule(Category.CRASH, Severity.LOW, rs.get(c).keywords()));
        assertThat(propose(rs, "test-admin-key").getBody().get("rejected").toString()).contains("크래시 심각도를 낮추는");

        // 모든 글을 「기타」로 보내는 규칙을 맨 위에 → 회귀·정확도 미달
        List<ClassificationRule> broken = current();
        broken.add(0, new ClassificationRule(Category.OTHER, Severity.LOW, List.of("요")));
        assertThat(propose(broken, "test-admin-key").getBody().get("rejected").toString()).contains("평가 관문 미달");
    }

    @Test
    void 되돌리면_원래_규칙으로_돌아간다() throws Exception {
        long id = ((Number) propose(withKeyword(index(Category.UX), "글자가 겹쳐"), "test-agent-key").getBody().get("versionId")).longValue();
        HttpHeaders h = new HttpHeaders();
        h.set("X-Api-Key", "test-admin-key");
        var r = http.exchange("/api/admin/classifier/rules/versions/" + id + "/revert", HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(r.getBody()).containsEntry("status", "REVERTED");
        assertThat(classifier.classify("g", "아이콘 글자가 겹쳐 보여요").category()).isNotEqualTo(Category.UX);
        assertThat(http.exchange("/api/admin/classifier/rules/versions/" + id + "/revert", HttpMethod.POST, new HttpEntity<>(h), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 키_없이는_못_낸다() throws Exception {
        assertThat(propose(current(), "wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 사람이_파일을_고치면_파일이_이긴다() {
        var fileRules = new java.util.concurrent.atomic.AtomicReference<>(List.of(new ClassificationRule(Category.BUG, Severity.MEDIUM, List.of("버그"))));
        RuleSource file = () -> new RuleSet(fileRules.get(), "파일", java.time.OffsetDateTime.now(), null);
        var v = new RuleVersion("{\"rules\":[{\"category\":\"UX\",\"severity\":\"LOW\",\"keywords\":[\"버그\"]}]}",
                ManagedRuleSource.fingerprint(fileRules.get()), "ai-agent", null, "x", 1, 1, 1);
        RuleVersionRepository repo = org.mockito.Mockito.mock(RuleVersionRepository.class);
        org.mockito.Mockito.when(repo.findFirstByStatusOrderByIdDesc(RuleVersion.Status.ACTIVE)).thenReturn(java.util.Optional.of(v));
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(repo);
        var managed = new ManagedRuleSource(file, provider);
        assertThat(managed.current().rules().get(0).category()).isEqualTo(Category.UX); // AI 수정이 위
        fileRules.set(List.of(new ClassificationRule(Category.BUG, Severity.HIGH, List.of("버그"))));
        assertThat(managed.current().rules().get(0).severity()).isEqualTo(Severity.HIGH); // 파일이 바뀌면 파일
    }
}
