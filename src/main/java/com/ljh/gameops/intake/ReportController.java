// 제보 등록 REST API — 게임 클라이언트나 웹폼이 직접 보낼 때 쓴다
package com.ljh.gameops.intake;

import com.ljh.gameops.report.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final IntakeService intake;

    public ReportController(IntakeService intake) { this.intake = intake; }

    public record ReportRequest(@NotBlank String game, @NotBlank String authorId,
                                @NotBlank @Size(max = 4000) String body,
                                @Size(max = 100) String externalId, String attachmentUrls) {}

    @PostMapping
    public ResponseEntity<IntakeResult> submit(@Valid @RequestBody ReportRequest req) {
        IntakeResult r = intake.submit(new SubmitReport(req.game(), SourceChannel.API, req.externalId(),
                req.authorId(), req.body(), req.attachmentUrls()));
        HttpStatus status = r.outcome() == IntakeResult.Outcome.NEW_ISSUE ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(r);
    }
}
