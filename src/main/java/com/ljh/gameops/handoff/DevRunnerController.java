// 개발 AI 러너가 쓰는 길(결정 49) — 일 가져가기·결과 돌려주기. 맥의 dev-runner 컨테이너만 부른다(관리자 키)
package com.ljh.gameops.handoff;

import com.ljh.gameops.common.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class DevRunnerController {
    private final DevFixService fixes;
    private final AdminGuard guard;

    public DevRunnerController(DevFixService fixes, AdminGuard guard) {
        this.fixes = fixes;
        this.guard = guard;
    }

    @PostMapping("/api/admin/dev-jobs/claim")
    @Operation(summary = "개발 AI 일 하나 가져가기", description = "수정안 쓰기(PLAN)나 고치기(FIX). 일이 없거나 러너가 꺼져 있으면 204")
    @SecurityRequirement(name = "adminKey")
    public ResponseEntity<DevFixService.Job> claim(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        return fixes.claim().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record ResultRequest(Boolean ok, String output, String branch, List<String> files, String error) {}

    @PostMapping("/api/admin/dev-jobs/{id}/result")
    @Operation(summary = "개발 AI 일 결과", description = "PLAN 은 output 에 수정안 JSON, FIX 는 branch·files·output(요약 + FIXED 줄)")
    @SecurityRequirement(name = "adminKey")
    public Map<String, Object> result(@RequestHeader(value = "X-Api-Key", required = false) String apiKey, @PathVariable long id,
                                      @RequestBody ResultRequest req) {
        guard.requireAdmin(apiKey);
        fixes.report(id, new DevFixService.Result(Boolean.TRUE.equals(req.ok()), DevFixService.cut(req.output(), 20_000), req.branch(),
                req.files(), DevFixService.cut(req.error(), 600)));
        return Map.of("ok", true);
    }
}
