-- 사람이 자동 판단을 고친 기록. 쌓이면 규칙·기준값을 어디서 고쳐야 하는지 데이터로 보인다.
CREATE TABLE correction (
    id          BIGSERIAL PRIMARY KEY,
    game_id     BIGINT       NOT NULL REFERENCES game(id),
    kind        VARCHAR(20)  NOT NULL,          -- DETACH(잘못 묶인 제보 떼어내기) / RECLASSIFY(분류·심각도 고치기)
    issue_id    BIGINT       NOT NULL REFERENCES issue(id),
    report_id   BIGINT       REFERENCES report(id),
    new_issue_id BIGINT      REFERENCES issue(id),
    before_value VARCHAR(100),
    after_value  VARCHAR(100),
    similarity  DOUBLE PRECISION,               -- 떼어낸 제보가 묶일 때의 유사도(기준값 조정 근거)
    auto_by     VARCHAR(20),                    -- 고치기 전 판단을 누가 했나(rules / llm)
    actor       VARCHAR(100) NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_correction_game ON correction (game_id, created_at DESC);
