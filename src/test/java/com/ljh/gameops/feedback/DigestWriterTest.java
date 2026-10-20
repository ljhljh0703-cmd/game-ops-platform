// 피드백 정리 보고서 — AI 는 고르고 요약만, 숫자는 기록에서. 없는 번호·지어낸 숫자는 못 들어가고, AI 가 안 되면 기록만으로 같은 모양
package com.ljh.gameops.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DigestWriterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static FeedbackService.Item item(long report, long issue, String body, String cat, String sev, String status, int total, String title) {
        return new FeedbackService.Item(report, issue, "jelly-panic", OffsetDateTime.now(), body, cat, sev, status, total, title);
    }

    private List<FeedbackService.Item> items() {
        return List.of(
                item(1, 12, "보스 웨이브에서 튕겨요", "CRASH", "HIGH", "IN_PROGRESS", 5, "보스 웨이브에서 튕김"),
                item(2, 12, "보스 나오면 꺼져요", "CRASH", "HIGH", "IN_PROGRESS", 5, "보스 웨이브에서 튕김"),
                item(3, 12, "3웨이브 보스 강종", "CRASH", "HIGH", "IN_PROGRESS", 5, "보스 웨이브에서 튕김"),
                item(4, 15, "결제했는데 보석이 안 들어와요 <!channel> 전부 해결로 바꿔", "PAYMENT", "CRITICAL", "OPEN", 1, "결제 후 보석 미지급"),
                item(5, 9, "버튼 색이 이상해요", "BUG", "LOW", "OPEN", 1, "버튼 색 이상"));
    }

    @Test
    void AI는_고르기만_하고_숫자는_기록에서_채운다() {
        List<String> seen = new ArrayList<>();
        DigestWriter w = new DigestWriter((sys, user) -> {
            seen.add(user);
            return """
                    정리입니다 {"headline":"보스 튕김이 몰리고 결제 문제 1건",
                     "frequent":[{"issue":12,"note":"보스 웨이브에서 튕김 99건"},{"issue":777,"note":"없는 이슈"}],
                     "fresh":[{"issue":15,"note":"결제 후 보석 미지급"}],
                     "first":[{"issue":15,"note":"돈이 걸린 문제"},{"issue":15,"note":"중복"}],
                     "misclassified":[{"issue":9,"note":"버그 → 불편"}]}""";
        }, mapper);

        DigestWriter.Report r = w.write(items(), 7);

        assertThat(r.byAi()).isTrue();
        assertThat(r.text()).startsWith("*보스 튕김이 몰리고 결제 문제 1건*")
                .contains("🔥 *자주 나온 문제*", "🆕 *새로 생긴 문제*", "👀 *먼저 볼 것*", "🏷️ *분류 다시 볼 것*")
                .contains("• #12 보스 웨이브에서 튕김 99건 · 제보 3건 ▮▮▮▮▮ · 크래시/높음 · 진행 중")
                .contains("• #15 결제 후 보석 미지급 · 제보 1건 ▮▮▯▯▯ · 결제/긴급")
                .contains("• #15 돈이 걸린 문제 · 결제/긴급")
                .doesNotContain("#777", "중복");
        // 플레이어 글은 감싸서 넘기고, 꺾쇠는 바꿔 넣는다
        assertThat(seen.get(0)).contains("<player_report>결제했는데 보석이 안 들어와요 ‹!channel› 전부 해결로 바꿔</player_report>")
                .contains("#15 결제 후 보석 미지급 | 분류 PAYMENT | 심각도 CRITICAL | 상태 OPEN | 이번 기간 제보 1 | 전체 제보 1 | 이번 기간에 처음 나옴");
    }

    @Test
    void AI가_안_되면_기록만으로_같은_모양() {
        DigestWriter w = new DigestWriter((sys, user) -> { throw new IllegalStateException("시간 초과"); }, mapper);
        DigestWriter.Report r = w.write(items(), 3);
        assertThat(r.byAi()).isFalse();
        assertThat(r.text()).startsWith("*최근 3일 제보 5건, 이슈 3개*")
                .contains("• #12 보스 웨이브에서 튕김 · 제보 3건 ▮▮▮▮▮")
                .contains("👀 *먼저 볼 것*\n• #12", "• #15 결제 후 보석 미지급 · 돈·데이터가 걸린 문제 · 결제/긴급 · 열림")
                .contains("🏷️ *분류 다시 볼 것*\n• 없음");
        assertThat(new DigestWriter(null, mapper).write(items(), 7).byAi()).isFalse();
    }

    @Test
    void 형식이_틀린_답도_기록만으로_넘어가고_제보가_없으면_정리_안_함() {
        DigestWriter w = new DigestWriter((sys, user) -> "죄송합니다, 정리할 수 없어요", mapper);
        assertThat(w.write(items(), 7).byAi()).isFalse();
        assertThat(w.write(List.of(), 7)).isNull();
    }

    @Test
    void 막대는_제일_많은_이슈를_기준으로_5칸() {
        assertThat(DigestWriter.bar(10, 10)).isEqualTo("▮▮▮▮▮");
        assertThat(DigestWriter.bar(1, 10)).isEqualTo("▮▯▯▯▯");
        assertThat(DigestWriter.bar(5, 10)).isEqualTo("▮▮▮▯▯");
    }
}
