// 「피드백 정리해」 한 번에 — 최근 제보 읽기 → 보고서 쓰기(AI, 안 되면 기록만으로) → 기록하고 슬랙 카드로.
// 슬랙 명령(/피드백정리)과 관리 API 가 같이 쓴다. AI 호출은 트랜잭션 밖에서.
package com.ljh.gameops.feedback;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AutoDigest {
    public static final int DEFAULT_DAYS = 7;
    static final int MAX_ITEMS = 300;

    private final FeedbackService feedback;
    private final DigestWriter writer;

    public AutoDigest(FeedbackService feedback, DigestWriter writer) {
        this.feedback = feedback;
        this.writer = writer;
    }

    /** posted=false 면 제보가 없어 올리지 않음 */
    public record Result(boolean posted, Long digestId, int reportCount, boolean byAi) {}

    /** requester = 감사 기록에 남길 요청자(slack:U… 또는 관리자 키 이름). AI 가 쓰면 카드에는 AI 가 정리했다고 적는다 */
    public Result run(String gameCode, int days, String requester) {
        int d = Math.max(1, Math.min(31, days));
        List<FeedbackService.Item> items = feedback.recent(gameCode, d, MAX_ITEMS);
        DigestWriter.Report report = writer.write(items, d);
        if (report == null) return new Result(false, null, 0, false);
        String actor = report.byAi() ? "ai-agent" : requester;
        long id = feedback.post(gameCode, d, report.text(), actor);
        return new Result(true, id, items.size(), report.byAi());
    }
}
