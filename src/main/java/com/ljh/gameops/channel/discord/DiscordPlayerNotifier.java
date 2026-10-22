// 이슈가 해결되면 그 이슈에 묶인 디스코드 제보 글마다 「✅ 고쳤어요」 답장을 단다(같은 사람은 한 번만, 최대 25명).
// 원래 글이 지워졌으면 답장 대상 없이 채널에 올린다. 채널을 못 찾으면 건너뛴다. 봇이 아직 안 붙었으면 예외 → 대기열이 다시 보낸다.
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.game.Game;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.issue.Issue;
import com.ljh.gameops.issue.IssueReport;
import com.ljh.gameops.issue.IssueReportRepository;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.notify.PlayerNotifier;
import com.ljh.gameops.report.Report;
import com.ljh.gameops.report.ReportRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "gameops.discord", name = "enabled", havingValue = "true")
public class DiscordPlayerNotifier implements PlayerNotifier {
    private static final Logger log = LoggerFactory.getLogger(DiscordPlayerNotifier.class);
    static final int MAX_REPLIES = 25;

    private final DiscordGateway gateway;
    private final IssueRepository issues;
    private final IssueReportRepository links;
    private final ReportRepository reports;
    private final GameRepository games;

    public DiscordPlayerNotifier(DiscordGateway gateway, IssueRepository issues, IssueReportRepository links,
                                 ReportRepository reports, GameRepository games) {
        this.gateway = gateway;
        this.issues = issues;
        this.links = links;
        this.reports = reports;
        this.games = games;
    }

    /** 답장할 곳 「채널 ID:메시지 ID」 — 같은 사람(작성자 해시)은 처음 글 하나만. */
    record Target(String channelId, String messageId, String lang, String body) {
        Target(String channelId, String messageId) { this(channelId, messageId, null, null); }
    }

    static List<Target> targets(List<Report> rs) {
        Set<String> seen = new HashSet<>();
        List<Target> out = new ArrayList<>();
        for (Report r : rs.stream().sorted(Comparator.comparing(Report::getReceivedAt)).toList()) {
            String to = r.getReplyTo();
            if (to == null || !seen.add(r.getAuthorHash())) continue;
            int i = to.indexOf(':');
            if (i <= 0 || i == to.length() - 1) continue;
            out.add(new Target(to.substring(0, i), to.substring(i + 1), r.getLanguage(), r.getBody()));
            if (out.size() >= MAX_REPLIES) break;
        }
        return out;
    }

    @Override
    public int issueResolved(long issueId) {
        JDA jda = gateway.jda();
        if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) throw new IllegalStateException("디스코드 봇이 아직 연결되지 않았어요");
        Issue issue = issues.findById(issueId).orElse(null);
        if (issue == null) return 0;
        String game = games.findById(issue.getGameId()).map(Game::getName).orElse("?");
        List<Report> rs = links.findByIssueId(issueId).stream().map(IssueReport::getReportId)
                .map(id -> reports.findById(id).orElse(null)).filter(java.util.Objects::nonNull).toList();
        int sent = 0;
        for (Target t : targets(rs)) {
            MessageChannel ch = jda.getChannelById(MessageChannel.class, t.channelId());
            if (ch == null) {
                log.info("해결 답장: 채널을 못 찾아 건너뜀 channelId={}", t.channelId());
                continue;
            }
            // 플레이어 말로 답한다. 외국어 제보자는 한국어 이슈 제목 대신 자기 글로 알아보게
            var embed = com.ljh.gameops.report.Lang.isKorean(t.lang()) ? DiscordCards.resolved(game, issueId, issue.getTitle())
                    : DiscordCards.resolved(game, issueId, t.body(), t.lang());
            try {
                ch.sendMessageEmbeds(embed).setMessageReference(t.messageId()).failOnInvalidReply(false)
                        .mentionRepliedUser(true).submit().get(10, TimeUnit.SECONDS);
                sent++;
            } catch (Exception e) {
                // 권한 없음 등 다시 보내도 안 되는 경우가 많아 이 글만 건너뛴다(나머지 제보자는 받게)
                log.warn("해결 답장 실패 channelId={} messageId={}: {}", t.channelId(), t.messageId(), e.getMessage());
            }
        }
        return sent;
    }
}
