// Jira 연결 — 게임 회사 QA·개발팀이 쓰는 이슈 관리 도구로 버그 티켓을 보낸다(결정 32).
// 주소·메일·API 토큰(JIRA_BASE_URL·JIRA_EMAIL·JIRA_API_TOKEN)이 없으면 Jira 버튼만 안 보이고 나머지는 그대로 돈다
package com.ljh.gameops.jira;

import java.util.List;
import java.util.Map;

public interface JiraClient {
    record Created(String key, String url) {}

    /** @param done Jira 에서 완료 묶음(statusCategory=done) 상태인지 · by = 마지막으로 바꾼 사람(모르면 담당자, 그것도 없으면 null) */
    record Status(boolean done, String name, String by) {}

    /**
     * 버그 티켓 하나를 만든다. 우선순위를 받지 않는 프로젝트(팀 관리형 등)면 우선순위 없이 한 번 더 시도한다.
     * @param description Atlassian 문서 형식(ADF) 본문
     */
    Created create(String project, String summary, Map<String, Object> description, String priority, List<String> labels);

    Status status(String key);

    /** 그림 붙이기. 실패해도 티켓은 그대로라 false 만 */
    boolean attach(String key, String filename, byte[] data);

    boolean ready();

    String browseUrl(String key);
}
