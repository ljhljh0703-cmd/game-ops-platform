// 묶기 비교에 필요한 이슈 칸만 — 열린 이슈가 많을 때 엔티티 전체를 읽고 관리하지 않게(부하 시험 ③ · docs/LOAD.md)
package com.ljh.gameops.issue;

public record IssueCandidate(Long id, Category category, Category autoCategory, String normalizedText) {}
