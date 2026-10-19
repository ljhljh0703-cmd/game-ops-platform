-- 재발 표시. 최근 해결한 이슈와 비슷한 새 제보가 오면, 새 이슈를 만들되 어느 이슈의 재발로 보이는지 적어 둔다.
ALTER TABLE issue ADD COLUMN recurrence_of BIGINT REFERENCES issue(id);
ALTER TABLE issue ADD COLUMN recurrence_similarity DOUBLE PRECISION;
CREATE INDEX ix_issue_resolved ON issue (game_id, status, resolved_at);
