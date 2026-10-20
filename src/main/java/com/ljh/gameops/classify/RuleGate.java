// 규칙 관문 — 새 규칙을 받기 전에 확인한다. 통과면 null, 미달이면 이유를 돌려준다.
package com.ljh.gameops.classify;

import java.util.List;

public interface RuleGate {
    /** 확인 없이 받는다(시험용·관문 데이터가 없을 때). */
    RuleGate NONE = rules -> null;

    String reject(List<ClassificationRule> rules);
}
