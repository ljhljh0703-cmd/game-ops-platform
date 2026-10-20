-- AI(또는 관리자)가 API 로 반영한 분류 규칙 기록 — 운영 판단 자동 고치기(결정 26).
-- 시험(평가 묶음)을 통과한 수정안만 ACTIVE 로 들어가고, 슬랙 「되돌리기」로 REVERTED. 새 수정안이 오면 앞 것은 SUPERSEDED.
-- base_hash = 반영할 때의 파일 규칙 지문. 사람이 파일을 고치면 지문이 달라져 파일 규칙이 이긴다.
CREATE TABLE classifier_rule_version (
    id               BIGSERIAL PRIMARY KEY,
    rules_json       TEXT         NOT NULL,
    base_hash        VARCHAR(64)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    actor            VARCHAR(100) NOT NULL,
    reason           TEXT,
    summary          TEXT         NOT NULL,
    dev_before       DOUBLE PRECISION NOT NULL,
    dev_after        DOUBLE PRECISION NOT NULL,
    regression_total INT          NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reverted_at      TIMESTAMPTZ,
    reverted_by      VARCHAR(100)
);
CREATE INDEX ix_rule_version_status ON classifier_rule_version (status, id);
