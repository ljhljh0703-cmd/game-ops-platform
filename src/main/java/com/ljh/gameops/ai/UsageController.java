// API 사용량 보기 — 이번 달 AI 비용·예산·남은 돈, 깃허브 남은 요청 수
package com.ljh.gameops.ai;

import com.ljh.gameops.common.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "사용량", description = "AI·깃허브 API 사용량과 남은 예산·한도")
public class UsageController {
    private final AiUsage ai;
    private final ApiQuota quota;
    private final AdminGuard guard;

    public UsageController(AiUsage ai, ApiQuota quota, AdminGuard guard) {
        this.ai = ai;
        this.quota = quota;
        this.guard = guard;
    }

    public record UsageView(String month, long aiCalls, long aiFailed, long inputTokens, long outputTokens, BigDecimal costUsd,
                            BigDecimal budgetUsd, BigDecimal remainingUsd, int percent, Map<String, Long> callsByPurpose,
                            BigDecimal allTimeUsd, AiUsage.Prices prices, ApiQuota.GitHub github, AiUsage.Credit credit,
                            BigDecimal creditRemainingUsd, String summary) {}

    @GetMapping("/api/admin/usage")
    @Operation(summary = "API 사용량", description = "이번 달(한국 시간) 서버가 부른 AI 호출 수·토큰·비용(달러, 단가 설정으로 계산)·예산·남은 돈, 기록 시작 뒤 합계, 깃허브 남은 요청 수. 잔액을 적어 두면(AI_CREDIT_BALANCE_USD) 남은 크레딧 추정(관리 키가 있으면 Anthropic 공식 집계로). Claude 플러그인·Codex 사용은 각자 구독이라 여기 없다")
    @SecurityRequirement(name = "adminKey")
    public UsageView usage(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        AiUsage.Month m = ai.month();
        Map<String, Long> calls = new LinkedHashMap<>();
        m.callsByPurpose().forEach((k, v) -> calls.put(k, v[0]));
        return new UsageView(m.month(), m.calls(), m.failed(), m.inputTokens(), m.outputTokens(), m.cost(), m.budget(), m.remaining(),
                m.percent(), calls, ai.allTime(), ai.prices(), quota.github(), m.credit(),
                m.credit() == null ? null : m.credit().remaining(), m.line());
    }
}
