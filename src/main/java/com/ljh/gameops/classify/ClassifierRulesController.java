// 지금 적용 중인 분류 규칙 보기 + AI·관리자가 규칙 수정안을 내면 서버가 시험해 통과하면 바로 반영(운영 판단 자동 고치기, 결정 26)
package com.ljh.gameops.classify;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.ljh.gameops.common.AdminGuard;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/classifier")
@Tag(name = "분류 규칙", description = "지금 쓰는 키워드 규칙과 마지막 읽기 오류")
public class ClassifierRulesController {
    private final RuleBasedClassifier rules;
    private final AdminGuard guard;
    private final RuleChangeService changes;

    public ClassifierRulesController(RuleBasedClassifier rules, AdminGuard guard, RuleChangeService changes) {
        this.rules = rules;
        this.guard = guard;
        this.changes = changes;
    }

    public record ProposalRequest(com.fasterxml.jackson.databind.JsonNode rules, String reason) {}

    public record VersionView(long id, String status, String actor, String reason, List<String> changes, double devBefore,
                              double devAfter, int regressionTotal, OffsetDateTime createdAt, OffsetDateTime revertedAt, String revertedBy) {
        static VersionView of(RuleVersion v) {
            return new VersionView(v.getId(), v.getStatus().name(), v.getActor(), v.getReason(), List.of(v.getSummary().split("\n")),
                    v.getDevBefore(), v.getDevAfter(), v.getRegressionTotal(), v.getCreatedAt(), v.getRevertedAt(), v.getRevertedBy());
        }
    }

    @PostMapping("/rules/proposals")
    @Operation(summary = "규칙 수정안 내기(시험 통과 시 바로 반영)",
            description = "body.rules 는 config/classifier-rules.json 과 같은 모양({\"rules\":[...]}). 서버가 dev·회귀 문장으로 다시 시험하고, "
                    + "dev 정확도가 떨어지지 않고 결제·크래시 키워드를 빼거나 낮추지 않으면 바로 반영해 슬랙에 「되돌리기」 카드를 보낸다. "
                    + "통과 못 하면 applied=false 와 이유. AI 에이전트 키로도 낼 수 있다")
    @SecurityRequirement(name = "adminKey")
    public RuleChangeService.Result propose(@RequestBody ProposalRequest req,
                                            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        if (req == null || req.rules() == null) throw new IllegalArgumentException("rules 가 비어 있어요");
        return changes.apply(req.rules().toString(), actor, req.reason());
    }

    @GetMapping("/rules/versions")
    @Operation(summary = "API 로 반영한 규칙 기록(최근 50)")
    @SecurityRequirement(name = "adminKey")
    public List<VersionView> versions(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        return changes.list().stream().map(VersionView::of).toList();
    }

    @PostMapping("/rules/versions/{id}/revert")
    @Operation(summary = "반영한 규칙 되돌리기", description = "파일(사람) 규칙으로 돌아간다. 슬랙 카드의 「되돌리기」와 같다")
    @SecurityRequirement(name = "adminKey")
    public VersionView revert(@PathVariable long id, @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        return VersionView.of(changes.revert(id, actor));
    }

    public record RulesView(String source, OffsetDateTime loadedAt, String lastError, List<ClassificationRule> rules) {}

    @GetMapping("/rules")
    @Operation(summary = "분류 규칙 보기")
    @SecurityRequirement(name = "adminKey")
    public RulesView current(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        RuleSet s = rules.rules();
        return new RulesView(s.source(), s.loadedAt(), s.lastError(), s.rules());
    }
}
