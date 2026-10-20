-- AI 피드백 정리 — AI 가 최근 제보를 모아 정리한 글. 슬랙에 카드로 올리고 기록으로 남긴다(결정 27)
CREATE TABLE feedback_digest (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT REFERENCES game(id),
    days          INT          NOT NULL,
    report_count  INT          NOT NULL,
    text          TEXT         NOT NULL,
    actor         VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
