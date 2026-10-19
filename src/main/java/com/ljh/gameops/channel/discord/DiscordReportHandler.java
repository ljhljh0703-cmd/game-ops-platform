// 디스코드 제보 처리 — 지정 채널 글과 /report 명령을 접수하고 답장 문구를 돌려준다
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.intake.IntakeResult;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.report.SourceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DiscordReportHandler {
    private static final Logger log = LoggerFactory.getLogger(DiscordReportHandler.class);

    private final IntakeService intake;
    private final IssueService issues;
    private final DiscordProperties props;

    public DiscordReportHandler(IntakeService intake, IssueService issues, DiscordProperties props) {
        this.intake = intake;
        this.issues = issues;
        this.props = props;
    }

    /** 제보 채널에 올라온 일반 글. 봇 글·다른 채널 글·빈 글은 답하지 않는다. */
    public Optional<String> onChannelMessage(DiscordInbound in) {
        if (in.fromBot() || !props.reportChannelIds().contains(in.channelId())) return Optional.empty();
        if (in.content() == null || in.content().isBlank()) return Optional.empty();
        return Optional.of(accept(in));
    }

    /** /report 명령. 게임을 고르지 않으면 기본 게임으로 받는다. */
    public String onSlashCommand(DiscordInbound in) {
        if (in.content() == null || in.content().isBlank()) return "내용을 적어 주세요.";
        return accept(in);
    }

    private String accept(DiscordInbound in) {
        String game = (in.gameCode() == null || in.gameCode().isBlank()) ? props.defaultGame() : in.gameCode();
        try {
            IntakeResult r = intake.submit(new SubmitReport(game, SourceChannel.DISCORD, in.externalId(),
                    in.authorId(), in.content(), in.attachmentUrls()));
            var issue = issues.get(r.issueId());
            int count = issue.getReportCount();
            return switch (r.outcome()) {
                case NEW_ISSUE -> issue.getRecurrenceOf() == null
                        ? "접수했어요. 이슈 #%d 로 개발팀에 전달합니다.".formatted(r.issueId())
                        : "접수했어요. 이슈 #%d 로 전달합니다. 최근 고친 문제(#%d)가 다시 생긴 것 같아 함께 알렸어요."
                                .formatted(r.issueId(), issue.getRecurrenceOf());
                case ATTACHED_TO_EXISTING -> "비슷한 제보가 있어 이슈 #%d 에 합쳤어요. 같은 제보 %d건째입니다.".formatted(r.issueId(), count);
                case DUPLICATE_DELIVERY -> "이미 접수된 제보예요. 이슈 #%d".formatted(r.issueId());
            };
        } catch (NotFoundException e) {
            return "등록되지 않은 게임이에요: " + game;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            log.error("디스코드 제보 접수 실패 externalId={}", in.externalId(), e);
            return "지금 접수가 안 되고 있어요. 잠시 뒤 다시 올려 주세요.";
        }
    }
}
