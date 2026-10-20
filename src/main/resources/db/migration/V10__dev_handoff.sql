-- 개발 AI에게 넘기기(결정 29) — 피드백 정리 하나를 게임 저장소의 깃허브 이슈(개발 지시서)로 넘긴 기록.
-- 같은 정리·같은 게임은 한 번만(버튼을 두 번 눌러도 이슈 하나)
CREATE TABLE dev_handoff (
    id            BIGSERIAL PRIMARY KEY,
    digest_id     BIGINT       NOT NULL REFERENCES feedback_digest(id),
    game_id       BIGINT       NOT NULL REFERENCES game(id),
    repo          VARCHAR(200) NOT NULL,
    issue_number  INT          NOT NULL,
    issue_url     VARCHAR(500) NOT NULL,
    issue_count   INT          NOT NULL,
    actor         VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (digest_id, game_id)
);
