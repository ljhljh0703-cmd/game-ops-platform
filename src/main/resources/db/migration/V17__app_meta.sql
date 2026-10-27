-- 서버 상태 값(결정 46): 지난번 켜졌을 때 버전 등
CREATE TABLE app_meta (
    key   VARCHAR(64) PRIMARY KEY,
    value TEXT NOT NULL
);
