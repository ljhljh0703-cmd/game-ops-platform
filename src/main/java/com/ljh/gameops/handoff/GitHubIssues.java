// 깃허브에 이슈 만들기 — 「개발 AI에게 넘기기」가 게임 저장소에 개발 지시서를 올릴 때 쓴다.
// 토큰(GITHUB_TOKEN, 저장소 Issues 쓰기 권한)이 없으면 넘기기만 안 되고 나머지는 그대로 돈다
package com.ljh.gameops.handoff;

import java.util.List;

public interface GitHubIssues {
    record Created(int number, String url) {}

    /** 이슈를 만든다. 실패하면 이유를 담은 예외 */
    Created create(String repo, String title, String body, List<String> labels);

    /** PR 열기(결정 49 — 러너가 올린 브랜치를 기본 브랜치로). 실패하면 이유를 담은 예외 */
    default Created openPr(String repo, String branch, String title, String body) {
        throw new IllegalStateException("깃허브 토큰이 없어요(.env 의 GITHUB_TOKEN)");
    }

    /** PR 합치기(스쿼시). 실패하면 이유를 담은 예외 */
    default void mergePr(String repo, int number, String title) {
        throw new IllegalStateException("깃허브 토큰이 없어요(.env 의 GITHUB_TOKEN)");
    }

    /** 토큰이 있어 실제로 만들 수 있는지 */
    boolean ready();
}
