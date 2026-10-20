-- 슬랙에 올린 카드의 위치 — 이슈·규칙 반영·피드백 정리 카드가 어디(채널·ts)에 있는지와 마지막으로 사람이 한 일.
-- 한 이슈에 카드가 여러 장일 수 있다(새 이슈 카드, 심각도 상승 카드). 바뀌면 전부 바꾼다.
-- 슬랙 버튼이 아닌 곳(API·AI 에이전트·플러그인 명령)에서 바꿔도 이 위치의 카드를 그 자리에서 바꾼다.
CREATE TABLE slack_message (
    kind        VARCHAR(20)  NOT NULL,
    ref_id      BIGINT       NOT NULL,
    channel     VARCHAR(40)  NOT NULL,
    ts          VARCHAR(40)  NOT NULL,
    last_action VARCHAR(300),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, ref_id, ts)
);
