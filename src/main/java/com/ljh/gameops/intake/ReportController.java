// 제보 등록 REST API — 게임 클라이언트나 웹폼이 직접 보낼 때 쓴다
package com.ljh.gameops.intake;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.ljh.gameops.report.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "제보", description = "디스코드 말고 게임 클라이언트·웹폼에서 직접 제보를 넣을 때")
public class ReportController {
    private final IntakeService intake;

    public ReportController(IntakeService intake) { this.intake = intake; }

    public record ReportRequest(@NotBlank String game, @NotBlank String authorId,
                                @NotBlank @Size(max = 4000) String body,
                                @Size(max = 100) String externalId, String attachmentUrls) {}

    @PostMapping
    @Operation(summary = "제보 등록", description = "새 이슈면 201, 기존 이슈에 붙거나 같은 externalId 재전송이면 200")
    public ResponseEntity<IntakeResult> submit(@Valid @RequestBody ReportRequest req) {
        IntakeResult r = intake.submit(new SubmitReport(req.game(), SourceChannel.API, req.externalId(),
                req.authorId(), req.body(), req.attachmentUrls()));
        HttpStatus status = r.outcome() == IntakeResult.Outcome.NEW_ISSUE ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(r);
    }
}
