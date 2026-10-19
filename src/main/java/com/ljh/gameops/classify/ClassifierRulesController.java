// 지금 적용 중인 분류 규칙을 보여 준다 — 파일을 고친 뒤 제대로 들어갔는지 확인하는 용도
package com.ljh.gameops.classify;

import com.ljh.gameops.common.AdminGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/classifier")
public class ClassifierRulesController {
    private final RuleBasedClassifier rules;
    private final AdminGuard guard;

    public ClassifierRulesController(RuleBasedClassifier rules, AdminGuard guard) {
        this.rules = rules;
        this.guard = guard;
    }

    public record RulesView(String source, OffsetDateTime loadedAt, String lastError, List<ClassificationRule> rules) {}

    @GetMapping("/rules")
    public RulesView current(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        RuleSet s = rules.rules();
        return new RulesView(s.source(), s.loadedAt(), s.lastError(), s.rules());
    }
}
