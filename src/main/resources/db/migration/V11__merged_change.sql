-- 고친 코드가 들어오면 자동 해결(결정 30) — 게임 저장소에서 합쳐진 PR·기본 브랜치 커밋 중 이미 처리한 것.
-- 서버를 다시 켜도 같은 PR·커밋으로 두 번 해결·알림하지 않게
CREATE TABLE merged_change (
    id               BIGSERIAL PRIMARY KEY,
    repo             VARCHAR(200) NOT NULL,
    kind             VARCHAR(10)  NOT NULL,
    ref              VARCHAR(80)  NOT NULL,
    resolved_issues  VARCHAR(500) NOT NULL DEFAULT '',
    actor            VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (repo, kind, ref)
);
