// 패치노트 API — 초안은 관리자·AI 키 둘 다, 공지는 사람(관리자 키)만
package com.ljh.gameops.patch;

import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.common.ForbiddenException;
import com.ljh.gameops.notify.PatchCard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Tag(name = "패치노트", description = "고친 버그·바뀐 점을 플레이어에게 알리는 글: 초안 → 고치기 → 디스코드 공지")
public class PatchNoteController {
    private final PatchNoteService service;
    private final AdminGuard guard;

    public PatchNoteController(PatchNoteService service, AdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    public record DraftRequest(String game, Integer days) {}

    public record EditRequest(String text) {}

    @PostMapping("/api/admin/patch-notes")
    @Operation(summary = "패치노트 초안", description = "마지막 공지 뒤(최대 days 일, 기본 14) 해결된 버그와 게임 저장소에 합쳐진 PR 로 초안을 쓰고 게임 슬랙 채널에 카드로 올린다. 알릴 게 없으면 id 가 null")
    @SecurityRequirement(name = "adminKey")
    public PatchNoteService.Draft draft(@RequestBody DraftRequest req, @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        if (req == null || req.game() == null) throw new IllegalArgumentException("game 이 필요해요");
        int days = req.days() == null ? 14 : Math.max(1, Math.min(90, req.days()));
        return service.draft(req.game(), days, actor);
    }

    @GetMapping("/api/admin/patch-notes")
    @Operation(summary = "패치노트 목록", description = "최근 것부터")
    @SecurityRequirement(name = "adminKey")
    public List<PatchCard> list(@RequestParam(required = false) String game, @RequestParam(defaultValue = "10") int limit,
                                @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        return service.recent(game, Math.max(1, Math.min(50, limit)));
    }

    @PutMapping("/api/admin/patch-notes/{id}")
    @Operation(summary = "패치노트 고치기", description = "초안일 때만")
    @SecurityRequirement(name = "adminKey")
    public PatchCard edit(@PathVariable long id, @RequestBody EditRequest req, @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        service.edit(id, req == null ? null : req.text(), actor, null, null);
        return service.card(id);
    }

    @PostMapping("/api/admin/patch-notes/{id}/publish")
    @Operation(summary = "디스코드에 공지", description = "사람만(AI 에이전트 키는 403). 바깥에 나가는 글이라 사람이 확인하고 누른다")
    @SecurityRequirement(name = "adminKey")
    public PatchCard publish(@PathVariable long id, @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        if (AdminGuard.AGENT.equals(actor)) throw new ForbiddenException("AI 에이전트는 공지할 수 없어요. 슬랙 카드에서 사람이 눌러 주세요");
        service.publish(id, actor, null, null);
        return service.card(id);
    }
}
