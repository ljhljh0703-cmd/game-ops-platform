-- 승인하면 개발 AI가 고친다(결정 49) — 피드백 정리 하나·게임 하나마다 한 줄.
-- PLANNING(개발 AI가 코드를 읽고 수정안 쓰는 중) → PLAN_READY(슬랙에서 검토) → FIXING(승인, 고치는 중) → PR_OPEN → MERGED
-- 옆길: HELD(보류) · FAILED(실패, 다시 시도 가능). 맥의 러너가 PLANNING·FIXING 을 가져가 Codex 로 돌리고 결과를 돌려준다
CREATE TABLE dev_fix (
    id            BIGSERIAL PRIMARY KEY,
    digest_id     BIGINT       NOT NULL REFERENCES feedback_digest(id),
    game_id       BIGINT       NOT NULL REFERENCES game(id),
    repo          VARCHAR(200) NOT NULL,
    state         VARCHAR(20)  NOT NULL,
    brief_title   VARCHAR(300) NOT NULL,
    brief         TEXT         NOT NULL,
    ops_ids       VARCHAR(500) NOT NULL,
    plan          TEXT,
    choice        VARCHAR(5),
    issue_number  INT,
    issue_url     VARCHAR(500),
    branch        VARCHAR(200),
    pr_number     INT,
    pr_url        VARCHAR(500),
    summary       TEXT,
    files         TEXT,
    error         TEXT,
    attempts      INT          NOT NULL DEFAULT 0,
    claimed_at    TIMESTAMPTZ,
    actor         VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (digest_id, game_id)
);
CREATE INDEX dev_fix_queue ON dev_fix (state, claimed_at);
