-- AI 호출 기록 — 호출마다 쓴 토큰과 그때 단가로 계산한 비용(달러). 이번 달 합계로 예산을 지킨다(결정 34)
CREATE TABLE ai_usage (
    id            BIGSERIAL    PRIMARY KEY,
    at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    purpose       VARCHAR(20)  NOT NULL,
    model         VARCHAR(80)  NOT NULL,
    input_tokens  INT          NOT NULL DEFAULT 0,
    output_tokens INT          NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(12, 6) NOT NULL DEFAULT 0,
    ok            BOOLEAN      NOT NULL
);
CREATE INDEX ix_ai_usage_at ON ai_usage (at);

-- 게임이 보낸 오류 기록(결정 37) — 같은 오류(fingerprint)는 한 줄에 횟수만 늘린다
CREATE TABLE client_error (
    id           BIGSERIAL    PRIMARY KEY,
    game_id      BIGINT       NOT NULL REFERENCES game(id),
    fingerprint  VARCHAR(64)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    stack        TEXT,
    build_version VARCHAR(60),
    device       VARCHAR(200),
    context      VARCHAR(500),
    first_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    count        INT          NOT NULL DEFAULT 1,
    CONSTRAINT uq_client_error UNIQUE (game_id, fingerprint)
);
CREATE INDEX ix_client_error_last ON client_error (game_id, last_at);
