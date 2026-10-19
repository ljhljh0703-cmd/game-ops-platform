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

맥에서는 Finder 에서 `start.command` 를 두 번 누르면 된다(처음 한 번은 「확인되지 않은 개발자」 경고가 뜨면 우클릭 → 열기). 터미널에서는:

```bash
./start.sh
```

Docker 확인 → `.env` 자동 생성(관리자 키 무작위 생성) → 서버·DB 켜기 → 헬스체크까지 한 번에 한다. 직접 하려면 `cp .env.example .env` 뒤 `docker compose up -d --build`.

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
| POST | `/api/issues/{id}/reports/{reportId}/detach` | 잘못 묶인 제보를 떼어 새 이슈로. 관리자 키 |
| PATCH | `/api/issues/{id}/classification` | 분류·심각도 고치기(내리기 가능). 관리자 키 |
| GET | `/api/corrections?game=` · `/api/corrections/summary?game=` | 사람이 고친 기록과 요약(자주 틀리는 방향, 떼어낸 제보의 유사도). 관리자 키 |
| GET | `/api/admin/classifier/rules` | 지금 적용 중인 키워드 규칙과 마지막 오류. 관리자 키 |
| POST | `/slack/interactions` | 슬랙 버튼 (서명 검사) |
| GET | `/actuator/health` | 헬스체크 |

## 테이블

| 테이블 | 내용 |
|---|---|
| `game` | 운영 대상 게임 |
| `report` | 원본 제보. 작성자는 해시만 저장. `(채널, 외부 ID)` 고유 |
| `issue` | 대표 이슈. 분류·심각도·상태·제보 수·재발 의심 대상. 열린 이슈끼리 같은 본문 키 금지(부분 고유 인덱스) |
| `issue_report` | 이슈↔제보 연결과 유사도 |
| `audit_log` | 상태 변경·떼어내기·분류 고치기 기록 |
| `correction` | 사람이 자동 판단을 고친 기록. 고치기 전후 값, 묶일 때 유사도, 이유 |
| `notification_outbox` | 알림 대기열 |

## 설정 키

`.env.example` 참고. 주요 값: `DUPLICATE_THRESHOLD`(중복 기준, 기본 0.55) · `RECURRENCE_WINDOW_DAYS`(재발로 볼 기간, 기본 30일) · `LLM_*` · `SLACK_*` · `DISCORD_*` · `ADMIN_API_KEY` · `AUTHOR_HASH_SALT`.

## 분류 키워드 고치기

`config/classifier-rules.json` 을 열어 고치고 저장하면 다음 제보부터 반영된다(재시작 필요 없음). 형식이 틀리면 그 파일은 받지 않고 직전 규칙을 계속 쓴다. 들어갔는지는 `GET /api/admin/classifier/rules` 로 확인.

## 슬랙 버튼

`.env` 에 `SLACK_SIGNING_SECRET`(슬랙 앱 Basic Information > Signing Secret)과 `SLACK_ADMIN_USER_IDS`(버튼을 누를 수 있는 슬랙 멤버 ID, 쉼표로 여러 명)를 넣고 `start.sh` 를 다시 실행하면, 끝에 슬랙 버튼 주소가 나온다(`slack-request-url.txt` 에도 저장). 슬랙 앱 설정 Interactivity & Shortcuts 의 Request URL 에 넣는다. 인터넷에는 이 주소 하나만 열리고(`deploy/slack-gateway.conf`), 껐다 켜면 주소가 바뀐다.

## 테스트

```bash
./gradlew test
```

Docker가 있어야 한다(테스트용 PostgreSQL 컨테이너). Docker Compose 2.x 기준. 디스코드·슬랙은 가짜 이벤트와 가짜 클라이언트로 시험한다.

설계 결정과 기각한 대안은 `docs/decisions.md`, 만들면서 막힌 곳은 `docs/stuck-notes.md`.
