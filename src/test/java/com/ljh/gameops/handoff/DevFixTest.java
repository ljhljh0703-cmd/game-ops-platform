// 승인하면 개발 AI가 고친다(결정 49) — 정리 → 수정안 쓰기 일 → 러너가 수정안 → 슬랙 카드에 안마다 버튼 → 승인하면 지시서 이슈 + 고치기 일
// → 러너가 브랜치를 올리면 서버가 PR → 「합치기」. 실패하면 이유와 다시 시도
package com.ljh.gameops.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.slack.SlackCards;
import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.notify.DigestCardFactory;
import com.ljh.gameops.report.SourceChannel;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.element.ButtonElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(DevFixTest.FakeGitHubConfig.class)
@TestPropertySource(properties = "gameops.dev-runner.enabled=true")
class DevFixTest extends IntegrationTest {
    static class FakeGitHub implements GitHubIssues {
        final List<String[]> issues = new CopyOnWriteArrayList<>();
        final List<String[]> prs = new CopyOnWriteArrayList<>();
        final List<Integer> merged = new CopyOnWriteArrayList<>();
        public Created create(String repo, String title, String body, List<String> labels) {
            issues.add(new String[]{repo, title, body});
            return new Created(issues.size() + 40, "https://github.com/" + repo + "/issues/" + (issues.size() + 40));
        }
        public Created openPr(String repo, String branch, String title, String body) {
            prs.add(new String[]{repo, branch, title, body});
            return new Created(prs.size() + 70, "https://github.com/" + repo + "/pull/" + (prs.size() + 70));
        }
        public void mergePr(String repo, int number, String title) { merged.add(number); }
        public boolean ready() { return true; }
    }

    @TestConfiguration
    static class FakeGitHubConfig {
        @Bean @Primary FakeGitHub fakeGitHub() { return new FakeGitHub(); }
    }

    @Autowired FakeGitHub github;
    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;
    @Autowired FeedbackService feedback;
    @Autowired DevFixService fixes;
    @Autowired DigestCardFactory digestCards;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void reset() { github.issues.clear(); github.prs.clear(); github.merged.clear(); }

    private HttpHeaders key() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", "test-agent-key");
        return h;
    }

    private ResponseEntity<Map> claim() {
        return http.exchange("/api/admin/dev-jobs/claim", HttpMethod.POST, new HttpEntity<>(key()), Map.class);
    }

    private void result(long id, Map<String, Object> body) {
        var r = http.exchange("/api/admin/dev-jobs/" + id + "/result", HttpMethod.POST, new HttpEntity<>(body, key()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long opsId() { return jdbc.queryForObject("SELECT max(id) FROM issue", Long.class); }

    @Test
    void 정리가_올라오면_수정안_승인하면_고쳐서_PR_합치기까지() {
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));
        long d = feedback.post("jelly-panic", 7, "*보스 튕김이 몰렸어요*", "ai-agent");
        long ops = opsId();

        // 1) 수정안 쓰기 일: 지시서와 「JSON 하나만」. 한 번 가져가면 다시 안 준다
        var job = claim();
        assertThat(job.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(job.getBody()).containsEntry("kind", "PLAN").containsEntry("repo", "ljhljh0703-cmd/jelly-panic");
        assertThat((String) job.getBody().get("prompt")).contains("JSON 하나만", "파일은 하나도 바꾸지 마", "보스 웨이브에서 튕겨요", "[운영 " + ops + "]");
        assertThat(claim().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        long fixId = ((Number) job.getBody().get("id")).longValue();

        // 카드: 쓰는 중이라는 줄, 넘기기 버튼은 없다
        var card = SlackCards.digestCard(digestCards.current(d));
        assertThat(card.getBlocks().toString()).contains("수정안을 쓰는 중").doesNotContain("개발 AI에게 넘기기");

        // 2) 수정안이 오면 안마다 버튼. 앞뒤 잡글이 있어도 JSON 만 읽는다
        result(fixId, Map.of("ok", true, "output", "생각해 봤어요.\n{\"summary\":\"보스 체력바가 0일 때 나눗셈\",\"items\":[{\"ops\":" + ops
                + ",\"cause\":\"Boss.update 에서 0으로 나눔\",\"fix\":\"0이면 건너뛰기\",\"risk\":\"낮음\"}],\"options\":["
                + "{\"key\":\"A\",\"title\":\"0 검사만\",\"detail\":\"그 줄만\",\"recommended\":false},"
                + "{\"key\":\"B\",\"title\":\"체력 계산 정리\",\"detail\":\"체력 계산 함수 하나로\",\"recommended\":true}]}\n끝"));
        card = SlackCards.digestCard(digestCards.current(d));
        assertThat(card.getBlocks().stream().filter(b -> b instanceof SectionBlock).map(Object::toString).reduce("", String::concat))
                .contains("수정안 · 젤리 패닉", "보스 체력바가 0일 때 나눗셈", "운영 " + ops, "*B. 체력 계산 정리* 👍 추천");
        var buttons = card.getBlocks().stream().filter(b -> b instanceof ActionsBlock).flatMap(b -> ((ActionsBlock) b).getElements().stream())
                .map(e -> (ButtonElement) e).toList();
        assertThat(buttons).extracting(ButtonElement::getActionId).containsExactly("dev_fix_approve_A", "dev_fix_approve_B", "dev_fix_hold");
        assertThat(buttons.get(1).getValue()).isEqualTo(fixId + ":B");
        assertThat(buttons.get(1).getStyle()).isEqualTo("primary");

        // 3) B안 승인: 지시서 이슈에 승인한 안이 붙고, 고치기 일이 나온다
        fixes.approve(fixId, "B", "slack:U1", null, null);
        assertThat(github.issues).hasSize(1);
        assertThat(github.issues.get(0)[2]).contains("보스 웨이브에서 튕겨요", "운영자가 승인한 수정안 (B안)", "체력 계산 정리", "Boss.update 에서 0으로 나눔");
        assertThatThrownBy(() -> fixes.approve(fixId, "A", "slack:U1", null, null)).hasMessageContaining("고치는 중");
        var fix = claim().getBody();
        assertThat(fix).containsEntry("kind", "FIX").containsEntry("branch", "ops/fix-" + d + "-jelly-panic");
        assertThat((String) fix.get("prompt")).contains("승인한 안: **B. 체력 계산 정리**", "FIXED: 12, 15", "git commit·push 는 하지 않는다");
        assertThat((String) fix.get("commitTitle")).isEqualTo("fix: 체력 계산 정리 (ops-" + ops + ")");

        // 4) 브랜치가 오면 서버가 PR. FIXED 줄의 번호만 운영 번호로(합치면 해결되게)
        result(fixId, Map.of("ok", true, "branch", "ops/fix-" + d + "-jelly-panic", "files", List.of("src/boss.js", "test/boss.test.js"),
                "output", "체력 계산을 한 함수로 모았어요.\n테스트를 돌렸어요.\nFIXED: " + ops + ", 999"));
        assertThat(github.prs).hasSize(1);
        assertThat(github.prs.get(0)[2]).isEqualTo("[운영 고침] 체력 계산 정리");
        assertThat(github.prs.get(0)[3]).contains("지시서: #41", "체력 계산을 한 함수로 모았어요", "`src/boss.js`", "ops-" + ops)
                .doesNotContain("ops-999", "FIXED:");
        card = SlackCards.digestCard(digestCards.current(d));
        assertThat(card.getBlocks().toString()).contains("PR 올라옴 · 젤리 패닉", "pull/71|#71", "바뀐 파일 2개", "dev_fix_merge");

        // 5) 합치기 → 합침. 운영 이슈 해결은 FixWatcher 가 합쳐진 PR 에서
        fixes.merge(fixId, "slack:U1", null, null);
        assertThat(github.merged).containsExactly(71);
        assertThat(SlackCards.digestCard(digestCards.current(d)).getBlocks().toString()).contains("합침", "고쳤어요");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE action LIKE 'DEV_FIX%' ORDER BY id", String.class))
                .containsExactly("DEV_FIX_PLAN", "DEV_FIX_APPROVE", "DEV_FIX_PR", "DEV_FIX_MERGE");
    }

    @Test
    void 실패하면_이유와_다시_시도_수정안_모양이_틀리면_한_안만() {
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "상점에서 멈춰요", null));
        long d = feedback.post("jelly-panic", 7, "*상점 멈춤*", "ai-agent");
        long fixId = ((Number) claim().getBody().get("id")).longValue();

        result(fixId, Map.of("ok", false, "error", "맥에서 Codex 로그인이 안 되어 있어요"));
        var card = SlackCards.digestCard(digestCards.current(d));
        assertThat(card.getBlocks().toString()).contains("Codex 로그인", "dev_fix_retry");

        fixes.retry(fixId, "slack:U1", null, null);
        assertThat(claim().getBody()).containsEntry("kind", "PLAN");
        result(fixId, Map.of("ok", true, "output", "JSON 을 못 썼어요. 상점 코드를 보면 됩니다"));
        var plan = fixes.forDigest(d).get(0).plan();
        assertThat(plan.summary()).contains("상점 코드를 보면");
        assertThat(plan.options()).extracting(DevFixService.Option::key).containsExactly("A");

        // 고치기에서 바뀐 게 없으면 실패
        fixes.approve(fixId, "A", "slack:U1", null, null);
        long again = ((Number) claim().getBody().get("id")).longValue();
        result(again, Map.of("ok", true, "output", "이미 고쳐져 있어요\nFIXED:", "files", List.of()));
        assertThat(fixes.forDigest(d).get(0).state()).isEqualTo(DevFixService.State.FAILED);
        assertThat(fixes.forDigest(d).get(0).error()).contains("바꾼 파일이 없어요");
        assertThat(github.prs).isEmpty();
    }

    @Test
    void 운영_번호_읽기() {
        assertThat(DevFixService.fixedOps("요약\nFIXED: 3, 5", List.of(3L, 4L))).containsExactly(3L);
        assertThat(DevFixService.fixedOps("요약만", List.of(3L, 4L))).containsExactly(3L, 4L);
        assertThat(DevFixService.fixedOps("FIXED:", List.of(3L))).isEmpty();
        assertThat(DevFixService.ops("### 1. [운영 7] a\n### 2. [운영 9] b\n[운영 7]")).containsExactly("7", "9");
    }
}
