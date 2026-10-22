-- 보류함(결정 44): 제보가 몰리는 동안 실적 없는 사람의 제보를 잃지 않고 받아 두었다가, 몰림이 풀리면 차례로 접수한다
CREATE TABLE held_report (
    id           BIGSERIAL PRIMARY KEY,
    game_id      BIGINT       NOT NULL REFERENCES game(id),
    author_hash  VARCHAR(64)  NOT NULL,
    external_id  VARCHAR(100),
    payload      TEXT         NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX held_report_game ON held_report (game_id, id);
CREATE INDEX held_report_author ON held_report (author_hash, received_at);
CREATE UNIQUE INDEX held_report_external ON held_report (external_id) WHERE external_id IS NOT NULL;
