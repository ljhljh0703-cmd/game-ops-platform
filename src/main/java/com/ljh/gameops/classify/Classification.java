// 분류 결과 — 어떤 분류기가 냈는지도 함께 남긴다
package com.ljh.gameops.classify;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;

public record Classification(Category category, Severity severity, String title, String classifiedBy) {}
