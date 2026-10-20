// 게임 목록과 주간 보고 미리보기 — AI 에이전트가 「어떤 게임이 있고 지금 어떤 상태인지」부터 알 수 있게
package com.ljh.gameops.api;

import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.report.WeeklyReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;

@RestController
@RequestMapping("/api/games")
@Tag(name = "게임", description = "운영 중인 게임 목록과 주간 보고")
public class GameController {
    private final GameCatalog catalog;
    private final GameRepository games;
    private final IssueRepository issues;
    private final WeeklyReportService weekly;
    private final Clock clock;

    public GameController(GameCatalog catalog, GameRepository games, IssueRepository issues,
                          WeeklyReportService weekly, Clock clock) {
        this.catalog = catalog;
        this.games = games;
        this.issues = issues;
        this.weekly = weekly;
        this.clock = clock;
    }

    public record GameView(String code, String name, long openIssues, List<String> discordChannels, String slackChannel) {}

    public record WeeklyPreview(String game, OffsetDateTime from, OffsetDateTime to, String text) {}

    @GetMapping
    @Operation(summary = "게임 목록", description = "games.json 의 게임과 게임별 미해결(접수·진행 중) 이슈 수")
    public List<GameView> list() {
        var open = EnumSet.of(IssueStatus.OPEN, IssueStatus.IN_PROGRESS);
        return catalog.all().stream().map((GameSpec g) -> new GameView(g.code(), g.name(),
                games.findByCode(g.code()).map(e -> issues.countByGameIdAndStatusIn(e.getId(), open)).orElse(0L),
                g.discordChannels(), g.slackChannel())).toList();
    }

    @GetMapping("/{code}/weekly-report")
    @Operation(summary = "주간 보고 미리보기", description = "지금 시각 기준 지난 days 일을 보고 문구로 만든다. 슬랙으로 보내지는 않는다")
    public WeeklyPreview weekly(@PathVariable String code,
                                @Parameter(description = "보고 기간(일), 1~31") @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 31) throw new IllegalArgumentException("days 는 1~31");
        OffsetDateTime to = OffsetDateTime.now(clock);
        OffsetDateTime from = to.minusDays(days);
        return new WeeklyPreview(code, from, to, weekly.build(code, from, to));
    }
}
