-- 외국어 제보(결정 42): 제보 언어와 AI 한국어 번역. 예전 제보는 한국어로 본다
ALTER TABLE report ADD COLUMN language VARCHAR(8);
ALTER TABLE report ADD COLUMN translation_ko TEXT;
