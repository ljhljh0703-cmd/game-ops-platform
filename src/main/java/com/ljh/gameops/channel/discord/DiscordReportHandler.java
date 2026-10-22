// 디스코드 제보 처리 — 게임별 제보 채널 글과 /report 명령을 접수하고 답장 문구를 돌려준다.
// 어느 게임 제보인지는 채널로 정한다(games.json 의 discordChannels).
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.intake.IntakeResult;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueService;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.report.Lang;
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

    /** 답장 한 건 — 글(text)과 카드에 쓸 값. 오류 답장은 issueId 가 null. */
    /** @param missing 새 이슈일 때 플레이어에게 더 물어볼 재현 정보(게임 버전·기기·재현 순서·스크린샷). 다 있으면 빈 목록 */
    /** @param lang 플레이어 글의 언어 — 답장도 그 말로(결정 42) */
    public record Reply(String text, Kind kind, Long issueId, String gameName, Category category, Severity severity, int reportCount,
                        java.util.List<String> missing, String lang) {
        public enum Kind { NEW, ATTACHED, DUPLICATE, HELD, ERROR }

        public Reply {
            missing = missing == null ? java.util.List.of() : java.util.List.copyOf(missing);
            lang = PlayerText.of(lang);
        }

        public Reply(String text, Kind kind, Long issueId, String gameName, Category category, Severity severity, int reportCount,
                     java.util.List<String> missing) {
            this(text, kind, issueId, gameName, category, severity, reportCount, missing, Lang.KO);
        }

        public Reply(String text, Kind kind, Long issueId, String gameName, Category category, Severity severity, int reportCount) {
            this(text, kind, issueId, gameName, category, severity, reportCount, java.util.List.of(), Lang.KO);
        }

        static Reply error(String text) { return error(text, Lang.KO); }

        static Reply error(String text, String lang) { return new Reply(text, Kind.ERROR, null, null, null, null, 0, null, lang); }
    }

    /** 제보를 받는 채널인지(끊어 쓴 글을 모으기 전에 거른다) */
    public boolean isReportChannel(DiscordInbound in) {
        return channelGame(in).isPresent();
    }

    /** 제보 채널에 올라온 일반 글. 봇 글·제보 채널이 아닌 곳의 글·빈 글은 답하지 않는다. */
    public Optional<String> onChannelMessage(DiscordInbound in) {
        return onChannelMessageReply(in).map(Reply::text);
    }

    public Optional<Reply> onChannelMessageReply(DiscordInbound in) {
        if (in.fromBot()) return Optional.empty();
        Optional<String> game = channelGame(in);
        if (game.isEmpty()) return Optional.empty();
        if (in.content() == null || in.content().isBlank()) return Optional.empty();
        String chosen = (in.gameCode() != null && !in.gameCode().isBlank()) ? in.gameCode().trim() : game.get();
        return Optional.ofNullable(accept(in, chosen, true));
    }

    /**
     * 제보 채널에서 답글로 단 글. 답글 대상이 이미 받은 제보면 재현 정보로 더하고 「더했어요」, 아니면 보통 제보로 받는다.
     * @param originalMessageId 답글이 가리키는 플레이어 원래 글의 메시지 ID(봇 답장에 단 답글이면 봇 답장이 가리키는 글)
     */
    public Optional<Reply> onReplyMessage(DiscordInbound in, String originalMessageId) {
        boolean noText = in.content() == null || in.content().isBlank();
        if (in.fromBot() || noText && in.attachmentUrls() == null) return Optional.empty();
        if (channelGame(in).isEmpty()) return Optional.empty();
        try {
            Optional<Long> issueId = intake.addRepro(SourceChannel.DISCORD, "msg:" + originalMessageId, in.content(), in.attachmentUrls());
            if (issueId.isPresent()) {
                var issue = issues.get(issueId.get());
                String name = games.byCode(channelGame(in).get()).map(GameSpec::name).orElse("");
                String lang = Lang.detect(in.content());
                return Optional.of(new Reply("[" + name + "] " + PlayerText.reproAdded(lang, issueId.get()),
                        Reply.Kind.ATTACHED, issueId.get(), name, issue.getCategory(), issue.getSeverity(), issue.getReportCount(), null, lang));
            }
        } catch (RuntimeException e) {
            log.warn("재현 정보 더하기 실패, 보통 제보로 받습니다 original={}", originalMessageId, e);
        }
        return onChannelMessageReply(in);
    }

    /** /report 명령. 게임을 고르면 그 게임, 안 고르면 명령을 쓴 채널의 게임, 그것도 아니면 기본 게임. */
    public String onSlashCommand(DiscordInbound in) {
        return onSlashCommandReply(in).text();
    }

    public Reply onSlashCommandReply(DiscordInbound in) {
        if (in.content() == null || in.content().isBlank()) return Reply.error(PlayerText.emptyBody(Lang.KO));
        String game = (in.gameCode() != null && !in.gameCode().isBlank()) ? in.gameCode().trim()
                : channelGame(in).orElse(props.defaultGame());
        return accept(in, game, false);
    }

    /**
     * 막힌 사람에게는 10분에 한 번만 답한다(결정 43). 도배하는 쪽이 봇 답장까지 불려 채널을 두 배로 채우지 못하게.
     * 작성자 ID 를 메모리에만 잠깐 둔다(재시작하면 잊는다)
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> toldLimited = new java.util.concurrent.ConcurrentHashMap<>();
    static final long QUIET_MS = 10 * 60_000L;

    boolean firstLimitedReply(String authorId) {
        long now = System.currentTimeMillis();
        if (toldLimited.size() > 10_000) toldLimited.values().removeIf(t -> now - t > QUIET_MS);
        Long before = toldLimited.get(authorId);
        if (before != null && now - before < QUIET_MS) return false;
        toldLimited.put(authorId, now);
        return true;
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

    /** @param quietRepeat 채널 글이면 true — 막힌 사람에게 두 번째부터는 답하지 않는다(null). 명령은 답해야 해서 false */
    private Reply accept(DiscordInbound in, String game, boolean quietRepeat) {
        // 어느 채널 글을 어느 게임으로 받았는지 남긴다(게임이 엉뚱하게 잡히면 이 줄로 원인을 본다)
        log.info("디스코드 제보 접수 channelId={} channelName={} game={}", in.channelId(), in.channelName(), game);
        String lang = Lang.detect(in.content());
        try {
            IntakeResult r = intake.submit(new SubmitReport(game, SourceChannel.DISCORD, in.externalId(),
                    in.authorId(), in.content(), in.attachmentUrls(), in.replyTo(), in.repro(), in.authorRoles()));
            if (r.outcome() == IntakeResult.Outcome.HELD) {
                String name = games.byCode(game).map(GameSpec::name).orElse(game);
                return new Reply("[" + name + "] " + PlayerText.held(lang), Reply.Kind.HELD, null, name, null, null, 0, null, lang);
            }
            var issue = issues.get(r.issueId());
            int count = issue.getReportCount();
            String name = games.byCode(game).map(GameSpec::name).orElse(game);
            Reply.Kind kind = switch (r.outcome()) {
                case NEW_ISSUE -> Reply.Kind.NEW;
                case ATTACHED_TO_EXISTING -> Reply.Kind.ATTACHED;
                case DUPLICATE_DELIVERY, HELD -> Reply.Kind.DUPLICATE;
            };
            String text = "[" + name + "] " + switch (r.outcome()) {
                case NEW_ISSUE -> issue.getRecurrenceOf() == null ? PlayerText.received(lang, r.issueId())
                        : PlayerText.receivedRecurrence(lang, r.issueId(), issue.getRecurrenceOf());
                case ATTACHED_TO_EXISTING -> PlayerText.attached(lang, r.issueId(), count);
                case DUPLICATE_DELIVERY, HELD -> PlayerText.duplicate(lang, r.issueId());
            };
            // 새 이슈면 빠진 재현 정보를 플레이어에게 묻는다(QA가 먼저 묻는 것). 합쳐진 제보는 이미 누군가 적었을 수 있어 묻지 않는다
            java.util.List<String> missing = kind == Reply.Kind.NEW
                    ? com.ljh.gameops.report.ReproInfo.merge(in.repro(), com.ljh.gameops.report.ReproInfo.parse(in.content()))
                            .missing(in.attachmentUrls() != null && !in.attachmentUrls().isBlank())
                    : java.util.List.of();
            return new Reply(text, kind, r.issueId(), name, issue.getCategory(), issue.getSeverity(), count, missing, lang);
        } catch (NotFoundException e) {
            return Reply.error(PlayerText.unknownGame(lang, game), lang);
        } catch (com.ljh.gameops.intake.RateLimitedException e) {
            if (quietRepeat && !firstLimitedReply(in.authorId())) return null;
            return Reply.error(e.reason() == com.ljh.gameops.intake.IntakeGuard.Reason.FLOOD
                    ? PlayerText.flood(lang) : PlayerText.rateLimited(lang), lang);
        } catch (IllegalArgumentException e) {
            return Reply.error(e.getMessage(), lang);
        } catch (RuntimeException e) {
            log.error("디스코드 제보 접수 실패 externalId={}", in.externalId(), e);
            return Reply.error(PlayerText.down(lang), lang);
        }
    }
}
