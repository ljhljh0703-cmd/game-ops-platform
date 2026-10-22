// 공격 막기(결정 44) — 신뢰 등급은 한 일로(실적·역할), 계정 나이는 보지 않는다. 몰리면 새 사람 제보는 보류함에 받아 뒀다가 풀리면 접수
package com.ljh.gameops.intake;

import com.ljh.gameops.IntegrationTest;
import com.ljh.gameops.issue.IssueRepository;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxRepository;
import com.ljh.gameops.report.SourceChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntakeGuardTest extends IntegrationTest {
    @Autowired IntakeService intake;
    @Autowired IntakeGuard guard;
    @Autowired HeldReleaser releaser;
    @Autowired IssueRepository issues;
    @Autowired OutboxRepository outbox;

    @AfterEach
    void restore() {
        guard.floodPerMin = 30;
        guard.memberRoles = Set.of();
        guard.staffRoles = Set.of();
    }

    private SubmitReport cmd(String author, String body, String id, List<String> roles) {
        return new SubmitReport("gemtower", SourceChannel.DISCORD, id, author, body, null, null, null, roles);
    }

    private IntakeResult submit(String author, String body, String id) {
        return intake.submit(cmd(author, body, id, List.of()));
    }

    /** 이 사람 제보가 들어간 이슈를 운영자가 다뤘다(작업 중으로) */
    private void handled(IntakeResult r) {
        var i = issues.findById(r.issueId()).orElseThrow();
        jdbc.update("UPDATE issue SET status = 'IN_PROGRESS' WHERE id = ?", i.getId());
    }

    @Test
    void 새_사람은_10분에_3건_오래된_계정이어도_같다() {
        // 계정 나이를 보지 않는다: 잠복 계정도 실적이 없으면 새 사람
        for (int i = 0; i < 3; i++) submit("100000000000000001", "서로 다른 글 " + i + "번 버그", "n" + i);
        assertThatThrownBy(() -> submit("100000000000000001", "또 다른 버그", "n3")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void 사람이_다룬_이슈에_제보가_들어가면_확인된_사람() {
        String a = "author-a";
        assertThat(guard.level(cmd(a, "x", null, List.of()))).isEqualTo(IntakeGuard.Level.NEW);
        handled(submit(a, "5웨이브에서 게임이 튕겨요", "c1"));
        assertThat(guard.level(cmd(a, "x", null, List.of()))).isEqualTo(IntakeGuard.Level.CONFIRMED);
    }

    @Test
    void 다룬_이슈_3개_서로_다른_날_2일이면_믿는_사람() {
        String a = "author-b";
        handled(submit(a, "5웨이브에서 게임이 튕겨요", "t1"));
        handled(submit(a, "결제했는데 보석이 안 들어왔어요", "t2"));
        handled(submit(a, "루비 타워 공격력이 너무 약해요", "t3"));
        assertThat(guard.level(cmd(a, "x", null, List.of()))).isEqualTo(IntakeGuard.Level.CONFIRMED); // 하루에 몰아서는 안 됨
        jdbc.update("UPDATE report SET received_at = received_at - interval '3 days' WHERE external_id = 't1'");
        assertThat(guard.level(cmd(a, "x", null, List.of()))).isEqualTo(IntakeGuard.Level.TRUSTED);
    }

    @Test
    void 역할로_멤버와_운영진() {
        guard.memberRoles = IntakeGuard.roles("후원자");
        guard.staffRoles = IntakeGuard.roles("999");
        assertThat(guard.level(cmd("m", "x", null, List.of("111", "후원자")))).isEqualTo(IntakeGuard.Level.MEMBER);
        assertThat(guard.level(cmd("s", "x", null, List.of("999", "운영")))).isEqualTo(IntakeGuard.Level.STAFF);
        for (int i = 0; i < 12; i++) intake.submit(cmd("s", "운영진 시험 글 " + i, "s" + i, List.of("999")));
    }

    @Test
    void 몰리면_새_사람은_보류함에_받고_실적_있는_사람은_그대로_풀리면_차례로_접수() {
        handled(submit("veteran", "5웨이브에서 게임이 튕겨요", "v0"));
        guard.floodPerMin = 3;
        for (int i = 0; i < 2; i++) submit("p" + i, "5웨이브에서 게임이 튕겨요", "f" + i);
        IntakeResult held = submit("newbie", "5웨이브에서 게임이 튕겨요ㅠ", "f-new");
        assertThat(held.outcome()).isEqualTo(IntakeResult.Outcome.HELD);
        assertThat(submit("newbie", "5웨이브에서 게임이 튕겨요ㅠ", "f-new").outcome()).isEqualTo(IntakeResult.Outcome.HELD); // 같은 메시지 한 번만
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_report", Integer.class)).isEqualTo(1);
        IntakeResult vet = submit("veteran", "5웨이브에서 게임이 튕겨요!!", "v1");
        assertThat(vet.outcome()).isEqualTo(IntakeResult.Outcome.ATTACHED_TO_EXISTING);
        assertThat(outbox.findByTypeOrderByIdAsc(NotificationType.OPS_ALERT)).hasSize(1);

        assertThat(releaser.release()).isZero(); // 아직 몰리는 중
        guard.floodPerMin = 100;
        assertThat(releaser.release()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_report", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report WHERE external_id = 'f-new'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 슬랙_API_제보는_막지_않는다() {
        for (int i = 0; i < 8; i++) intake.submit(new SubmitReport("gemtower", SourceChannel.API, "a" + i, "ops", "5웨이브 튕김 " + i, null));
    }
}
