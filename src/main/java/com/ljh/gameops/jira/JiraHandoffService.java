// Jira 로 보내기(결정 32) — 피드백 정리에 든 열린 이슈마다 그 게임 Jira 프로젝트에 버그 티켓 하나.
// 개발 AI에게 넘기는 깃허브 지시서는 「한 묶음」, Jira 는 QA·개발팀이 하나씩 맡아 닫는 「한 건에 한 장」. 같은 이슈는 한 번만 만든다.
// 디스코드 스크린샷은 주소가 하루쯤 지나면 닫혀서, 만들 때 내려받아 티켓에 직접 붙인다(최대 3장, 8MB까지)
package com.ljh.gameops.jira;

import com.ljh.gameops.audit.AuditLog;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameSpec;
import com.ljh.gameops.handoff.HandoffBrief;
import com.ljh.gameops.notify.DigestCard;
import com.ljh.gameops.notify.DigestChanged;
import com.ljh.gameops.notify.IssueChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
public class JiraHandoffService {
    private static final Logger log = LoggerFactory.getLogger(JiraHandoffService.class);
    static final int MAX_ITEMS = 300, MAX_SHOTS = 3, MAX_BYTES = 8 * 1024 * 1024;

    private final FeedbackService feedback;
    private final GameCatalog games;
    private final JiraClient jira;
    private final JiraTickets tickets;
    private final JdbcTemplate jdbc;
    private final AuditLogRepository audit;
    private final ApplicationEventPublisher events;
    /** 스크린샷 내려받기(시험에서 바꿔 끼운다). 못 받으면 null */
    Function<String, byte[]> download = JiraHandoffService::fetch;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.ljh.gameops.errors.ClientErrors clientErrors;

    public JiraHandoffService(FeedbackService feedback, GameCatalog games, JiraClient jira, JiraTickets tickets,
                              JdbcTemplate jdbc, AuditLogRepository audit, ApplicationEventPublisher events) {
        this.feedback = feedback;
        this.games = games;
        this.jira = jira;
        this.tickets = tickets;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    /** created = 이번에 만든 티켓 키, already = 전에 만들어 둔 티켓 수 */
    public record Result(String gameName, String project, List<String> created, int already) {}

    /** 이 정리 범위에서 Jira 로 보낼 수 있는 게임이 있는지(카드에 버튼을 보일지) */
    public boolean available(String digestGameCode) {
        return jira.ready() && targets(digestGameCode).stream().anyMatch(g -> g.jiraProject() != null);
    }

    public synchronized List<Result> handoff(long digestId, String actor, String channel, String ts) {
        FeedbackService.Digest d = feedback.get(digestId);
        List<Result> out = new ArrayList<>();
        List<Long> touched = new ArrayList<>();
        boolean anyProject = false;
        for (GameSpec g : targets(d.gameCode())) {
            if (g.jiraProject() == null) continue;
            anyProject = true;
            List<HandoffBrief.Row> rows = HandoffBrief.rows(feedback.recent(g.code(), d.days(), MAX_ITEMS));
            if (rows.isEmpty()) continue;
            List<String> created = new ArrayList<>();
            int already = 0;
            for (HandoffBrief.Row r : rows) {
                if (tickets.of(r.id()).isPresent()) {
                    already++;
                    continue;
                }
                JiraBrief.Ticket t = JiraBrief.build(g.name(), g.code(), r, d.days(),
                        clientErrors == null ? List.of() : clientErrors.forIssue(r.id(), 3));
                JiraClient.Created c = jira.create(g.jiraProject(), t.summary(), t.description(), t.priority(), t.labels());
                tickets.save(r.id(), c.key(), c.url(), digestId, actor);
                attachShots(c.key(), r.repro().screenshots());
                audit.save(new AuditLog(actor, "JIRA_TICKET", "issue:" + r.id(), g.code() + " → " + c.key()));
                created.add(c.key());
                touched.add(r.id());
            }
            out.add(new Result(g.name(), g.jiraProject(), created, already));
        }
        if (!anyProject) throw new IllegalStateException("이 게임은 Jira 프로젝트가 설정에 없어요(games.json 의 jiraProject)");
        if (!touched.isEmpty() || channel != null) events.publishEvent(new DigestChanged(digestId, channel, ts));
        touched.forEach(id -> events.publishEvent(IssueChanged.counts(id)));
        return out;
    }

    /** 카드에 그릴 Jira 티켓 기록(이 정리에서 만든 것) */
    public List<DigestCard.Handoff> tickets(long digestId) {
        return jdbc.query("""
                SELECT url, jira_key, actor, created_at FROM jira_ticket WHERE digest_id = ? ORDER BY issue_id
                """, (rs, i) -> new DigestCard.Handoff(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class)), digestId);
    }

    private List<GameSpec> targets(String gameCode) {
        return gameCode != null ? games.byCode(gameCode).map(List::of).orElse(List.of()) : games.all();
    }

    private void attachShots(String key, List<String> urls) {
        int n = 0;
        for (String u : urls) {
            if (n >= MAX_SHOTS) break;
            if (!discordCdn(u)) continue;
            byte[] data = download.apply(u);
            if (data == null || data.length == 0 || data.length > MAX_BYTES) continue;
            if (jira.attach(key, filename(u, ++n), data)) log.info("Jira {} 스크린샷 붙임 {}", key, n);
        }
    }

    /** 디스코드가 올려 둔 첨부만 내려받는다(플레이어가 아무 주소나 넣어 서버가 엉뚱한 곳을 부르지 않게) */
    static boolean discordCdn(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost();
            return "https".equals(u.getScheme()) && (host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String filename(String url, int n) {
        String path = URI.create(url).getPath();
        String last = path.substring(path.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (last.isBlank() || last.length() > 80) last = "screenshot-" + n + ".png";
        return last;
    }

    private static byte[] fetch(String url) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
