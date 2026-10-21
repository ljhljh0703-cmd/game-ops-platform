// 이슈 조회·상태 변경 REST API — 상태 변경은 관리자 키가 있어야 한다
package com.ljh.gameops.issue;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.ljh.gameops.report.ReportRepository;

import com.ljh.gameops.common.AdminGuard;
import com.ljh.gameops.common.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/issues")
@Tag(name = "이슈", description = "비슷한 제보를 묶은 단위. 조회는 키 없이, 상태 변경은 관리자 키")
public class IssueController {
    private final IssueService service;
    private final AdminGuard guard;
    private final ReportRepository reports;

    public IssueController(IssueService service, AdminGuard guard, ReportRepository reports) {
        this.service = service;
        this.guard = guard;
        this.reports = reports;
    }

    public record IssueView(long id, String title, Category category, Severity severity, IssueStatus status,
                            int reportCount, String classifiedBy, Long recurrenceOf, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static IssueView of(Issue i) {
            return new IssueView(i.getId(), i.getTitle(), i.getCategory(), i.getSeverity(), i.getStatus(),
                    i.getReportCount(), i.getClassifiedBy(), i.getRecurrenceOf(), i.getCreatedAt(), i.getUpdatedAt());
        }
    }

    public record IssueDetail(IssueView issue, List<Long> reportIds) {}

    public record StatusRequest(@NotNull IssueStatus status, @Size(max = 300) String reason) {}

    public record ReportView(long reportId, String body, String sourceChannel, double similarity, OffsetDateTime receivedAt) {}

    @GetMapping
    @Operation(summary = "이슈 목록", description = "게임 코드로 거르고, status 를 주면 그 상태만")
    public List<IssueView> list(@RequestParam String game, @RequestParam(required = false) IssueStatus status) {
        return service.list(game, status).stream().map(IssueView::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "이슈 하나", description = "이슈와 묶인 제보 번호들")
    public IssueDetail get(@PathVariable long id) {
        return new IssueDetail(IssueView.of(service.get(id)),
                service.reportsOf(id).stream().map(IssueReport::getReportId).toList());
    }

    @GetMapping("/{id}/reports")
    @Operation(summary = "이슈에 묶인 제보 본문", description = "잘못 묶였는지·분류가 맞는지 판단할 때 본다. 플레이어 글이라 관리자 키가 필요하다. 작성자는 해시만 저장하므로 내보내지 않는다")
    @SecurityRequirement(name = "adminKey")
    public List<ReportView> reportsOf(@PathVariable long id,
                                      @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        guard.requireAdmin(apiKey);
        service.get(id); // 없는 이슈면 404
        return service.reportsOf(id).stream().map(link -> reports.findById(link.getReportId())
                .map(r -> new ReportView(r.getId(), r.getBody(), r.getSourceChannel().name(), link.getSimilarity(), r.getReceivedAt()))
                .orElseThrow()).toList();
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "이슈 상태 바꾸기", description = "OPEN·IN_PROGRESS·VERIFYING·RESOLVED·WONT_FIX. 감사 기록에 키 이름과 reason(선택)이 남는다. AI 에이전트 키로는 VERIFYING·RESOLVED·WONT_FIX 불가(403)")
    @SecurityRequirement(name = "adminKey")
    public IssueView changeStatus(@PathVariable long id, @Valid @RequestBody StatusRequest req,
                                  @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        String actor = guard.requireAdmin(apiKey);
        if (req == null || req.status() == null) throw new IllegalArgumentException("status 가 필요합니다");
        // 고쳤는지는 개발팀만 안다. 에이전트는 해결·보류로 닫지 못한다(플레이어 글에 숨은 지시로 이슈가 닫히는 것도 막는다)
        if (AdminGuard.AGENT.equals(actor) && (req.status() == IssueStatus.RESOLVED || req.status() == IssueStatus.WONT_FIX
                || req.status() == IssueStatus.VERIFYING)) {
            throw new ForbiddenException("AI 에이전트는 해결·보류로 바꿀 수 없습니다. 사람이 슬랙 버튼이나 관리자 키로 처리하세요");
        }
        String via = req.reason() == null || req.reason().isBlank() ? "api" : "api: " + req.reason().strip();
        return IssueView.of(service.changeStatus(id, req.status(), actor, via));
    }
}
