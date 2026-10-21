-- 재현 정보(게임 버전·기기·재현 순서) — QA가 버그 제보를 받으면 먼저 묻는 것. 스크린샷은 V1 attachment_urls
ALTER TABLE report ADD COLUMN build_version VARCHAR(60);
ALTER TABLE report ADD COLUMN device        VARCHAR(100);
ALTER TABLE report ADD COLUMN repro_steps   TEXT;

-- Jira 버그 티켓 — 운영 이슈 하나에 티켓 하나. 같은 이슈를 두 번 넘겨도 티켓은 하나(issue_id 고유)
CREATE TABLE jira_ticket (
    issue_id    BIGINT       PRIMARY KEY REFERENCES issue(id),
    jira_key    VARCHAR(40)  NOT NULL,
    url         VARCHAR(300) NOT NULL,
    digest_id   BIGINT,
    actor       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    done_at     TIMESTAMPTZ
);
CREATE INDEX ix_jira_ticket_open ON jira_ticket (done_at) WHERE done_at IS NULL;
