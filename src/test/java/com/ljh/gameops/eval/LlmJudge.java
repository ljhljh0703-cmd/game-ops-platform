// 묶기 평가용 — 운영과 같은 AI 판정 코드(DuplicateJudge.Llm)로 40쌍을 잰다
package com.ljh.gameops.eval;

import com.ljh.gameops.intake.DuplicateJudge;
import org.springframework.web.client.RestClient;

import java.util.List;

public final class LlmJudge {
    private final DuplicateJudge judge;

    public LlmJudge(RestClient client, String model, boolean anthropic) {
        this.judge = new DuplicateJudge.Llm(client, model, anthropic, null);
    }

    public List<DuplicateEvaluator.Scored> score(List<EvalData.Pair> pairs) {
        return pairs.stream().map(p -> new DuplicateEvaluator.Scored(p, judge.confidence("eval", p.a(), p.b()))).toList();
    }
}
