-- 게임 운영 플랫폼 첫 스키마 — 게임·제보·이슈·중복 묶음·감사 기록·알림 대기열

CREATE TABLE game (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 원본 제보. 같은 채널의 같은 메시지는 한 번만 저장한다(external_id).
CREATE TABLE report (
    id              BIGSERIAL PRIMARY KEY,
    game_id         BIGINT       NOT NULL REFERENCES game(id),
    source_channel  VARCHAR(20)  NOT NULL,
    external_id     VARCHAR(100),
    author_hash     VARCHAR(64)  NOT NULL,
    body            TEXT         NOT NULL,
    attachment_urls TEXT,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_external UNIQUE (source_channel, external_id)
);

CREATE TABLE issue (
    id                       BIGSERIAL PRIMARY KEY,
    game_id                  BIGINT       NOT NULL REFERENCES game(id),
    title                    VARCHAR(120) NOT NULL,
    category                 VARCHAR(20)  NOT NULL,
    severity                 VARCHAR(20)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    normalized_key           VARCHAR(200) NOT NULL,
    normalized_text          TEXT         NOT NULL,
    representative_report_id BIGINT       REFERENCES report(id),
    report_count             INT          NOT NULL DEFAULT 0,
    classified_by            VARCHAR(20)  NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at              TIMESTAMPTZ,
    version                  BIGINT       NOT NULL DEFAULT 0
);

-- 열린 이슈끼리는 같은 정규화 키가 두 번 생기지 않게 막는다(동시 제보 경합 대비).
CREATE UNIQUE INDEX uq_issue_open_key ON issue (game_id, normalized_key)
    WHERE status IN ('OPEN', 'IN_PROGRESS');
CREATE INDEX ix_issue_game_status ON issue (game_id, status);

CREATE TABLE issue_report (
    issue_id   BIGINT NOT NULL REFERENCES issue(id),
    report_id  BIGINT NOT NULL REFERENCES report(id) UNIQUE,
    similarity DOUBLE PRECISION NOT NULL,
    linked_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, report_id)
);

CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    actor      VARCHAR(100) NOT NULL,
    action     VARCHAR(50)  NOT NULL,
    target     VARCHAR(100) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 외부 알림은 DB 트랜잭션 안에서 대기열에 먼저 적고, 별도 작업이 보낸다.
CREATE TABLE notification_outbox (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(30)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_outbox_due ON notification_outbox (status, next_attempt_at);

INSERT INTO game (code, name) VALUES ('gemtower', '보석타워'), ('jelly-panic', '젤리 패닉');
