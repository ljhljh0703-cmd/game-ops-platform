// 슬랙 홈 현황판 모양 — 게임마다 4칸 숫자와 먼저 볼 이슈, 열린 이슈가 없을 때, 플레이어 글 서식 문자 무력화, 블록 수 한도
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.IssueStatus;
import com.ljh.gameops.issue.Severity;
import com.slack.api.util.json.GsonFactory;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlackHomeTest {
    @Test
    void 게임마다_숫자와_먼저_볼_이슈() {
        var rows = List.of(new SlackHome.IssueRow(12, "보스 웨이브에서 튕겨요 <!channel>", Category.CRASH, Severity.HIGH, IssueStatus.IN_PROGRESS, 5));
        var board = new SlackHome.Board(List.of(
                new SlackHome.GameBoard("보석타워", 2, 1, 1, 4, rows),
                new SlackHome.GameBoard("무브퓨", 0, 0, 0, 0, List.of())), "10/21 05:40 · 보석타워 · 제보 9건", null, OffsetDateTime.now());
        var v = SlackHome.view(board);
        assertThat(v.getType()).isEqualTo("home");
        assertThat(v.getBlocks().size()).isLessThan(100);
        String json = GsonFactory.createSnakeCase().toJson(v);
        assertThat(json).contains("*긴급·높음*\\n🔴 1건").contains("*이번 주 해결*\\n4건")
                .contains("🟠 #12 보스 웨이브에서 튕겨요").contains("🛠️ 진행 중")
                .contains("열린 이슈가 없어요").contains("10/21 05:40 · 보석타워").contains("아직 없음");
        assertThat(SlackHome.rows(rows)).contains("&lt;!channel&gt;").doesNotContain("<!channel>");
    }
}
