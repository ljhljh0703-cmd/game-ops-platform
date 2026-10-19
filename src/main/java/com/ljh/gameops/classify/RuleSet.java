// 지금 쓰는 규칙 묶음과 그 출처 — 운영자가 무엇이 적용 중인지 확인하는 데 쓴다
package com.ljh.gameops.classify;

import java.time.OffsetDateTime;
import java.util.List;

public record RuleSet(List<ClassificationRule> rules, String source, OffsetDateTime loadedAt, String lastError) {}
