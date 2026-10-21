// 처리 지표 보기 — 게임별 고칠 때까지·QA 확인까지 중앙값, 묶인 제보, 재현 정보 비율, 재발
package com.ljh.gameops.metrics;

import com.ljh.gameops.common.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Tag(name = "처리 지표", description = "QA·개발 리드가 보는 처리 지표(기록에서 계산)")
public class MetricsController {
    private final OpsMetrics metrics;
    private final AdminGuard guard;

    public MetricsController(OpsMetrics metrics, AdminGuard guard) {
        this.metrics = metrics;
        this.guard = guard;
    }

    public record View(String game, String name, int days, Long toFixSeconds, int fixedSamples, Long toVerifiedSeconds, int verifiedSamples,
                       long reports, long issues, long merged, int reproPercent, long recurrences, int recurrencePercent, long verifying) {
        static View of(OpsMetrics.GameMetrics m) {
            return new View(m.code(), m.name(), m.days(), m.toFix() == null ? null : m.toFix().toSeconds(), m.fixedSamples(),
                    m.toVerified() == null ? null : m.toVerified().toSeconds(), m.verifiedSamples(), m.reports(), m.issues(), m.merged(),
                    m.reproPercent(), m.recurrences(), m.recurrencePercent(), m.verifying());
        }
    }

    @GetMapping("/api/admin/metrics")
    @Operation(summary = "처리 지표", description = "최근 days 일(기본 30, 최대 365). 중앙값은 표본 3건 이상일 때만(아니면 null). game 을 비우면 모든 게임")
    @SecurityRequirement(name = "adminKey")
    public List<View> metrics(@RequestParam(required = false) String game, @RequestParam(defaultValue = "30") int days,
                              @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        int d = Math.max(1, Math.min(365, days));
        return (game == null || game.isBlank() ? metrics.all(d) : List.of(metrics.of(game, d))).stream().map(View::of).toList();
    }
}
