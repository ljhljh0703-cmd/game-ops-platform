# game-ops-platform

게임 운영 플랫폼. 플레이어가 디스코드에 올린 버그·불만을 서버가 받아 분류하고, 비슷한 제보를 하나의 이슈로 묶어 DB에 넣는다. 개발팀 슬랙에는 새 이슈 알림과 주간 보고가 가고, 알림의 버튼으로 이슈 상태를 바꾼다.

테스트 대상 게임: 보석타워(`gemtower`), 젤리 패닉(`jelly-panic`).

## 흐름

```
디스코드 제보 채널 글 / /report 명령 / REST API
        │
        ▼
  제보 접수 ── 같은 메시지 재수신이면 기존 결과 반환
        │
        ▼
  분류 (LLM → 실패 시 키워드 규칙)          ← DB 트랜잭션 밖
        │
        ▼
  ┌─ 한 트랜잭션 ────────────────────────────┐
  │ 제보 저장 → 열린 이슈와 유사도 비교        │
  │   ├ 비슷함: 이슈 잠그고 제보 붙이기        │
  │   └ 새 내용: 이슈 만들기                  │
  │ 알림 대기열에 기록                        │
  └──────────────────────────────────────────┘
        │
        ▼
  알림 발송 작업 (5초마다) ── 슬랙 실패 시 간격을 늘려 재시도, 5회 실패면 FAILED
                              (최소 한 번 전달: 드물게 같은 알림이 두 번 갈 수 있음)
        │
        ▼
  슬랙: 새 이슈 · 심각도 상승 · 주간 보고(월 09:00)
  슬랙 버튼 → 서명 확인 → 관리자 확인 → 상태 변경 + 감사 기록
```

## 기술

Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · JDA(디스코드) · Slack API Client · Testcontainers · Docker Compose

## 실행

```bash
cp .env.example .env        # 꼭 먼저 만든다. ADMIN_API_KEY, AUTHOR_HASH_SALT 를 영문·숫자로 바꾸기
docker compose up -d --build
curl localhost:8080/actuator/health
```

제보 올리기:

```bash
curl -H 'Content-Type: application/json' \
  -d '{"game":"gemtower","authorId":"tester-1","body":"보스 웨이브에서 튕겨요"}' \
  localhost:8080/api/reports
```

디스코드·슬랙·LLM은 기본으로 꺼져 있다. 꺼져 있으면 분류는 키워드 규칙으로, 알림은 서버 로그로 나간다. 켜려면 `.env`에 토큰을 넣고 `*_ENABLED=true`.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/reports` | 제보 등록. 새 이슈면 201, 기존 이슈에 붙으면 200 |
| GET | `/api/issues?game=gemtower&status=OPEN` | 이슈 목록 |
| GET | `/api/issues/{id}` | 이슈와 묶인 제보 번호 |
| PATCH | `/api/issues/{id}/status` | 상태 변경. `X-Api-Key` 관리자 키 필요 |
| POST | `/slack/interactions` | 슬랙 버튼 (서명 검사) |
| GET | `/actuator/health` | 헬스체크 |

## 테이블

| 테이블 | 내용 |
|---|---|
| `game` | 운영 대상 게임 |
| `report` | 원본 제보. 작성자는 해시만 저장. `(채널, 외부 ID)` 고유 |
| `issue` | 대표 이슈. 분류·심각도·상태·제보 수. 열린 이슈끼리 같은 본문 키 금지(부분 고유 인덱스) |
| `issue_report` | 이슈↔제보 연결과 유사도 |
| `audit_log` | 상태 변경 기록 |
| `notification_outbox` | 알림 대기열 |

## 설정 키

`.env.example` 참고. 주요 값: `DUPLICATE_THRESHOLD`(중복 기준, 기본 0.55) · `LLM_*` · `SLACK_*` · `DISCORD_*` · `ADMIN_API_KEY` · `AUTHOR_HASH_SALT`.

## 테스트

```bash
./gradlew test
```

Docker가 있어야 한다(테스트용 PostgreSQL 컨테이너). Docker Compose 2.x 기준. 디스코드·슬랙은 가짜 이벤트와 가짜 클라이언트로 시험한다.

설계 결정과 기각한 대안은 `docs/decisions.md`, 만들면서 막힌 곳은 `docs/stuck-notes.md`.
