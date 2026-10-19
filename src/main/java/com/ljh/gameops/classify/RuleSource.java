// 규칙을 어디서 가져오는가 — 파일이든 기본값이든
package com.ljh.gameops.classify;

public interface RuleSource {
    RuleSet current();
}
