-- 같은 알림을 두 번 넣지 않기 위한 키 — 서버가 여러 대일 때 주간 보고가 서버 수만큼 쌓이는 것 방지
ALTER TABLE notification_outbox ADD COLUMN dedupe_key VARCHAR(120);
CREATE UNIQUE INDEX uq_outbox_dedupe ON notification_outbox (dedupe_key) WHERE dedupe_key IS NOT NULL;
