-- 패치노트(결정 39) — 고친 버그와 바뀐 점을 플레이어에게 알리는 글. 초안 → 사람이 고치고 → 공지. 게임마다 마지막 공지 뒤부터 모은다
CREATE TABLE patch_note (
    id               BIGSERIAL    PRIMARY KEY,
    game_id          BIGINT       NOT NULL REFERENCES game(id),
    from_at          TIMESTAMPTZ  NOT NULL,
    to_at            TIMESTAMPTZ  NOT NULL,
    title            VARCHAR(200) NOT NULL,
    text             TEXT         NOT NULL,
    issue_ids        TEXT,
    status           VARCHAR(20)  NOT NULL,
    actor            VARCHAR(100) NOT NULL,
    by_ai            BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    edited_by        VARCHAR(100),
    published_by     VARCHAR(100),
    published_at     TIMESTAMPTZ,
    discord_channel  VARCHAR(40),
    discord_message  VARCHAR(40)
);
CREATE INDEX ix_patch_note_game ON patch_note (game_id, status, to_at);
