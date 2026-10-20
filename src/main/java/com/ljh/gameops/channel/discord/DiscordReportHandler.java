// 디스코드 제보 처리 — 게임별 제보 채널 글과 /report 명령을 접수하고 답장 문구를 돌려준다.
// 어느 게임 제보인지는 채널로 정한다(games.json 의 discordChannels).
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
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
    private final GameCatalog games;

    public DiscordReportHandler(IntakeService intake, IssueService issues, DiscordProperties props, GameCatalog games) {
        this.intake = intake;
        this.issues = issues;
        this.props = props;
        this.games = games;
        if (props.defaultGame() != null && games.byCode(props.defaultGame()).isEmpty() && !games.all().isEmpty()) {
            log.warn("기본 게임 {} 이(가) 게임 목록(games.json)에 없습니다. 이 게임 제보는 게임별 슬랙 채널 없이 공용 채널로 갑니다",
                    props.defaultGame());
        }
    }

    /** 제보 채널에 올라온 일반 글. 봇 글·제보 채널이 아닌 곳의 글·빈 글은 답하지 않는다. */
    public Optional<String> onChannelMessage(DiscordInbound in) {
        if (in.fromBot()) return Optional.empty();
        Optional<String> game = channelGame(in);
        if (game.isEmpty()) return Optional.empty();
        if (in.content() == null || in.content().isBlank()) return Optional.empty();
        String chosen = (in.gameCode() != null && !in.gameCode().isBlank()) ? in.gameCode().trim() : game.get();
        return Optional.of(accept(in, chosen));
    }

    /** /report 명령. 게임을 고르면 그 게임, 안 고르면 명령을 쓴 채널의 게임, 그것도 아니면 기본 게임. */
    public String onSlashCommand(DiscordInbound in) {
        if (in.content() == null || in.content().isBlank()) return "내용을 적어 주세요.";
        String game = (in.gameCode() != null && !in.gameCode().isBlank()) ? in.gameCode().trim()
                : channelGame(in).orElse(props.defaultGame());
        return accept(in, game);
    }

    /** 게임별 채널이 먼저, 예전 방식(DISCORD_REPORT_CHANNEL_IDS → 기본 게임)은 그다음. */
    private Optional<String> channelGame(DiscordInbound in) {
        Optional<String> mapped = games.forDiscordChannel(in.channelId(), in.channelName()).map(GameSpec::code);
        if (mapped.isPresent()) return mapped;
        if (props.reportChannelIds().contains(in.channelId())) {
            log.warn("게임 목록에 없는 채널이라 예전 설정(DISCORD_REPORT_CHANNEL_IDS)의 기본 게임 {} 으로 받습니다. channelId={} channelName={}",
                    props.defaultGame(), in.channelId(), in.channelName());
            return Optional.ofNullable(props.defaultGame());
        }
        return Optional.empty();
    }

    private String accept(DiscordInbound in, String game) {
        // 어느 채널 글을 어느 게임으로 받았는지 남긴다(게임이 엉뚱하게 잡히면 이 줄로 원인을 본다)
        log.info("디스코드 제보 접수 channelId={} channelName={} game={}", in.channelId(), in.channelName(), game);
        try {
            IntakeResult r = intake.submit(new SubmitReport(game, SourceChannel.DISCORD, in.externalId(),
                    in.authorId(), in.content(), in.attachmentUrls()));
            var issue = issues.get(r.issueId());
            int count = issue.getReportCount();
            String name = games.byCode(game).map(GameSpec::name).orElse(game);
            return "[" + name + "] " + switch (r.outcome()) {
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
