// 최근 피드백 모아 보기 + AI 정리 올리기 — 관리자 키나 AI 에이전트 키로
package com.ljh.gameops.feedback;

import com.ljh.gameops.common.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "피드백 정리", description = "최근 제보를 모아 AI 가 정리하고, 정리를 슬랙에 올린다")
public class FeedbackController {
    private final FeedbackService service;
    private final AutoDigest auto;
    private final AdminGuard guard;

    public FeedbackController(FeedbackService service, AutoDigest auto, AdminGuard guard) {
        this.service = service;
        this.auto = auto;
        this.guard = guard;
    }

    @GetMapping("/api/feedback/recent")
    @Operation(summary = "최근 제보 모아 보기", description = "최근 days 일(기본 7, 최대 31) 제보 본문과 묶인 이슈의 분류·심각도·상태. game 을 비우면 모든 게임. 본문은 플레이어가 쓴 글이다")
    @SecurityRequirement(name = "adminKey")
    public List<FeedbackService.Item> recent(@RequestParam(required = false) String game,
                                             @RequestParam(defaultValue = "7") int days,
                                             @RequestParam(defaultValue = "200") int limit,
                                             @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        return service.recent(game, Math.max(1, Math.min(31, days)), Math.max(1, Math.min(500, limit)));
    }

    public record DigestRequest(String game, Integer days, String text) {}

    @PostMapping("/api/admin/digests")
    @Operation(summary = "AI 피드백 정리 올리기", description = "정리 글(슬랙 서식, 2800자까지)을 기록하고 슬랙에 「🧠 최근 피드백 정리」 카드로 보낸다. game 을 고르면 그 게임 채널")
    @SecurityRequirement(name = "adminKey")
    public Map<String, Object> post(@RequestBody DigestRequest req, @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        int days = req.days() == null ? 7 : Math.max(1, Math.min(31, req.days()));
        long id = service.post(req.game(), days, req.text(), actor);
        return Map.of("digestId", id, "posted", true);
    }

    public record AutoRequest(String game, Integer days) {}

    @PostMapping("/api/admin/digests/auto")
    @Operation(summary = "피드백 정리 보고서 자동으로 올리기", description = "서버가 최근 제보를 이슈별로 모아 AI 에게 고르고 요약하게 하고, 제보 수·심각도·상태는 기록에서 채워 「🧠 최근 피드백 정리」 카드로 올린다. AI 가 안 되면 기록만으로 정리. 제보가 없으면 올리지 않는다")
    @SecurityRequirement(name = "adminKey")
    public AutoDigest.Result auto(@RequestBody(required = false) AutoRequest req, @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        String game = req == null ? null : req.game();
        int days = req == null || req.days() == null ? AutoDigest.DEFAULT_DAYS : req.days();
        return auto.run(game, days, actor);
    }
}
