// 키워드 규칙 한 줄 — 이 말들 중 하나가 들어 있으면 이 분류·심각도
package com.ljh.gameops.classify;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;

import java.util.List;

public record ClassificationRule(Category category, Severity severity, List<String> keywords) {}
