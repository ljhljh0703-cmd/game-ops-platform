// 게임 저장소에서 「고친 코드가 들어왔는지」 읽기 — 합쳐진 PR, 기본 브랜치 커밋. 공개 저장소는 읽기 권한만으로 된다.
// 처리 결과를 PR 에 댓글로 남기는 것은 쓰기 권한이 있을 때만(실패해도 해결은 그대로)
package com.ljh.gameops.handoff;

import java.time.OffsetDateTime;
import java.util.List;

public interface GitHubRepoReader {
    /** 합쳐진 PR(최근 갱신 순). mergedBy 는 합친 사람 */
    record Merged(int number, String title, String body, String mergedBy, OffsetDateTime mergedAt, String url) {}

    /** 기본 브랜치 커밋 */
    record Commit(String sha, String message, String author, OffsetDateTime at, String url) {}

    List<Merged> recentlyMerged(String repo);

    List<Commit> commitsSince(String repo, OffsetDateTime since);

    /** PR·이슈에 댓글. 못 달면 false */
    boolean comment(String repo, int number, String body);

    boolean ready();
}
