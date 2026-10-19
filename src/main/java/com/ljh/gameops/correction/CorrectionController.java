// 자동 판단 되돌리기 API — 모두 관리자 키가 있어야 한다
package com.ljh.gameops.correction;

import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueController.IssueView;
import com.ljh.gameops.issue.Severity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
public class CorrectionController {
    private final CorrectionService service;
    private final AdminGuard guard;

    public CorrectionController(CorrectionService service, AdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    public record ReasonRequest(String reason) {}
    public record ClassificationRequest(Category category, Severity severity, String reason) {}
    public record DetachView(IssueView original, IssueView created) {}
    public record CorrectionView(long id, CorrectionKind kind, long issueId, Long reportId, Long newIssueId,
                                 String before, String after, Double similarity, String autoBy,
                                 String actor, String reason, OffsetDateTime createdAt) {
        static CorrectionView of(Correction c) {
            return new CorrectionView(c.getId(), c.getKind(), c.getIssueId(), c.getReportId(), c.getNewIssueId(),
                    c.getBeforeValue(), c.getAfterValue(), c.getSimilarity(), c.getAutoBy(),
                    c.getActor(), c.getReason(), c.getCreatedAt());
        }
    }

    /** 잘못 묶인 제보를 떼어 새 이슈로 만든다. */
    @PostMapping("/api/issues/{id}/reports/{reportId}/detach")
    public DetachView detach(@PathVariable long id, @PathVariable long reportId,
                             @RequestBody(required = false) ReasonRequest req,
                             @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        var r = service.detach(id, reportId, actor, req == null ? null : req.reason());
        return new DetachView(IssueView.of(r.original()), IssueView.of(r.created()));
    }

    /** 분류·심각도를 사람이 고친다. 둘 중 하나만 보내도 된다. */
    @PatchMapping("/api/issues/{id}/classification")
    public IssueView reclassify(@PathVariable long id, @RequestBody ClassificationRequest req,
                                @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        return IssueView.of(service.reclassify(id, req.category(), req.severity(), actor, req.reason()));
    }

    @GetMapping("/api/corrections")
    public List<CorrectionView> list(@RequestParam String game,
                                     @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        return service.list(game).stream().map(CorrectionView::of).toList();
    }

    /** 어떤 자동 판단이 자주 틀리는지 요약. 키워드 규칙·묶기 기준값을 고칠 근거. */
    @GetMapping("/api/corrections/summary")
    public CorrectionSummary.View summary(@RequestParam String game,
                                          @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        return CorrectionSummary.of(service.all(game));
    }
}
