// 게임 오류 기록(결정 37) — 게임이 보낸 오류를 같은 지문끼리 묶고, 제보와 같은 시간대면 카드·지시서에 붙인다.
// 플레이어가 붙여 넣은 「게임진단」 줄은 버전·기기·오류로 받는다. 도배 막기(결정 36)도 여기서
package com.ljh.gameops.errors;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.channel.discord.DiscordInbound;
import com.ljh.gameops.channel.discord.DiscordReportHandler;
import com.ljh.gameops.feedback.FeedbackService;
import com.ljh.gameops.handoff.HandoffBrief;
import com.ljh.gameops.intake.IntakeService;
import com.ljh.gameops.intake.SubmitReport;
import com.ljh.gameops.notify.IssueCardFactory;
import com.ljh.gameops.report.ReproInfo;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientErrorsTest extends IntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired IntakeService intake;
    @Autowired IssueCardFactory cards;
    @Autowired FeedbackService feedback;
    @Autowired DiscordReportHandler handler;

    private ResponseEntity<Map> send(String game, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN); // sendBeacon 이 보내는 모양
        return http.exchange("/api/games/" + game + "/errors", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void 같은_오류는_한_줄에_횟수만_늘린다() {
        String e1 = "{\"message\":\"TypeError: Cannot read properties of undefined (reading 'hp')\",\"stack\":\"TypeError: x\\n    at Boss.update (game.js:120:15)\\n    at loop (game.js:30:3)\",\"version\":\"1.2.3\",\"device\":\"Chrome 130 · iPhone\",\"context\":\"3웨이브\"}";
        String e2 = e1.replace("game.js:120:15", "game.js:121:9").replace("'hp'", "'mp'"); // 줄 번호·따옴표 안 값만 다름
        assertThat(send("gemtower", e1).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        send("gemtower", e2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM client_error", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count FROM client_error", Integer.class)).isEqualTo(2);

        assertThat(send("nope", e1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send("gemtower", "not json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send("gemtower", "{\"message\":\"" + "x".repeat(20_000) + "\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 같은_시간대_오류가_카드와_지시서에_붙는다() {
        send("gemtower", "{\"message\":\"TypeError: hp is undefined\",\"stack\":\"TypeError: hp\\n    at Boss.update (game.js:120:15)\",\"version\":\"1.2.3\"}");
        long id = intake.submit(new SubmitReport("gemtower", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null)).issueId();
        intake.submit(new SubmitReport("jelly-panic", SourceChannel.API, null, "a", "보스 웨이브에서 튕겨요", null));

        assertThat(cards.current(id).errors()).isEqualTo("같은 시간대 게임 오류 1종 · TypeError: hp is undefined ×1 · v1.2.3");
        var other = jdbc.queryForObject("SELECT id FROM issue WHERE game_id = (SELECT id FROM game WHERE code = 'jelly-panic')", Long.class);
        assertThat(cards.current(other).errors()).isNull(); // 다른 게임 오류는 안 붙는다

        var brief = HandoffBrief.build("보석타워", "gemtower", 1, 7, "", feedback.recent("gemtower", 7, 50),
                x -> new ClientErrors(jdbc, java.time.Clock.systemUTC()).forIssue(x, 3));
        assertThat(brief.body()).contains("같은 시간대(제보 앞뒤 30분) 게임 오류 기록", "확정 아님", "TypeError: hp is undefined ×1", "at Boss.update (game.js:120:15)");
    }

    @Test
    void 제보에_붙여_넣은_게임진단_줄을_받는다() {
        String body = "보스전에서 멈췄어요 [게임진단 v1.4.2 · Chrome 130 · iPhone · 오류: TypeError: hp is undefined · at Boss]";
        assertThat(ReproInfo.diag(body)).isEqualTo(new ReproInfo.Diag("1.4.2", "Chrome 130, iPhone", "TypeError: hp is undefined · at Boss"));
        intake.submit(new SubmitReport("gemtower", SourceChannel.DISCORD, "msg:1", "p", body, null));

        assertThat(jdbc.queryForMap("SELECT build_version, device FROM report")).containsEntry("build_version", "1.4.2").containsEntry("device", "Chrome 130, iPhone");
        assertThat(jdbc.queryForMap("SELECT message, context FROM client_error"))
                .containsEntry("message", "TypeError: hp is undefined · at Boss").containsEntry("context", "제보 글의 게임진단");
        assertThat(ReproInfo.diag("그냥 글 [게임진단]")).isNull();
    }

    @Test
    void 실적_없는_사람이_10분에_3건_넘게_올리면_받지_않는다() {
        for (int i = 0; i < 3; i++) {
            var r = handler.onChannelMessageReply(new DiscordInbound("msg:" + i, "C_REPORT", null, "spammer", false, null, "버그 " + i + "번 상점이 안 열려요", null)).orElseThrow();
            assertThat(r.kind()).isNotEqualTo(DiscordReportHandler.Reply.Kind.ERROR);
        }
        var fourth = handler.onChannelMessageReply(new DiscordInbound("msg:9", "C_REPORT", null, "spammer", false, null, "또 버그", null)).orElseThrow();
        assertThat(fourth.kind()).isEqualTo(DiscordReportHandler.Reply.Kind.ERROR);
        assertThat(fourth.text()).contains("짧은 시간에 제보가 많아요");
        // 다른 사람은 그대로, 같은 메시지를 다시 받는 건 도배가 아님
        assertThat(handler.onChannelMessageReply(new DiscordInbound("msg:10", "C_REPORT", null, "other", false, null, "결제가 안 돼요", null))
                .orElseThrow().kind()).isEqualTo(DiscordReportHandler.Reply.Kind.NEW);
        assertThat(handler.onChannelMessageReply(new DiscordInbound("msg:1", "C_REPORT", null, "spammer", false, null, "버그 1번 상점이 안 열려요", null))
                .orElseThrow().kind()).isEqualTo(DiscordReportHandler.Reply.Kind.DUPLICATE);
    }
}
