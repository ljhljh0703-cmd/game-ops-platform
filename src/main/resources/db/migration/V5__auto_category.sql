-- 처음 자동 분류 — 사람이 분류를 고쳐도 남긴다. 「분류가 같을 때만 묶기」(P9)에서 지금 분류 또는 처음 자동 분류 중
-- 하나만 같아도 묶는다. 고친 뒤에도 같은 글은 자동으로 예전 분류가 나오므로, 이 칸이 없으면 새 이슈가 계속 생긴다.
ALTER TABLE issue ADD COLUMN auto_category VARCHAR(20);
UPDATE issue SET auto_category = category;
ALTER TABLE issue ALTER COLUMN auto_category SET NOT NULL;
