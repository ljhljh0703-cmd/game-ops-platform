// 이슈 조회·상태 변경 REST API — 상태 변경은 관리자 키가 있어야 한다
package com.ljh.gameops.issue;

import com.ljh.gameops.common.AdminGuard;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/issues")
public class IssueController {
    private final IssueService service;
    private final AdminGuard guard;

    public IssueController(IssueService service, AdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    public record IssueView(long id, String title, Category category, Severity severity, IssueStatus status,
                            int reportCount, String classifiedBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static IssueView of(Issue i) {
            return new IssueView(i.getId(), i.getTitle(), i.getCategory(), i.getSeverity(), i.getStatus(),
                    i.getReportCount(), i.getClassifiedBy(), i.getCreatedAt(), i.getUpdatedAt());
        }
    }

    public record IssueDetail(IssueView issue, List<Long> reportIds) {}

    public record StatusRequest(@NotNull IssueStatus status) {}

    @GetMapping
    public List<IssueView> list(@RequestParam String game, @RequestParam(required = false) IssueStatus status) {
        return service.list(game, status).stream().map(IssueView::of).toList();
    }

    @GetMapping("/{id}")
    public IssueDetail get(@PathVariable long id) {
        return new IssueDetail(IssueView.of(service.get(id)),
                service.reportsOf(id).stream().map(IssueReport::getReportId).toList());
    }

    @PatchMapping("/{id}/status")
    public IssueView changeStatus(@PathVariable long id, @RequestBody StatusRequest req,
                                  @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        if (req == null || req.status() == null) throw new IllegalArgumentException("status 가 필요합니다");
        return IssueView.of(service.changeStatus(id, req.status(), actor, "api"));
    }
}
