# game-ops-platform

게임 운영 플랫폼. 플레이어가 디스코드에 올린 버그·불만을 서버가 받아 분류하고, 비슷한 제보를 하나의 이슈로 묶어 DB에 넣는다. 개발팀 슬랙에는 새 이슈 알림과 주간 보고가 가고, 알림의 버튼으로 이슈 상태를 바꾼다.

여러 게임을 한 서버로 운영한다. 기본 목록: 젤리 패닉(`jelly-panic`), 보석타워(`gemtower`), 무브퓨(`movefu`) — `config/games.json`.

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

Docker(맥에서는 OrbStack 권장, Docker Desktop 도 됨) 확인 → `.env` 자동 생성(관리자 키 무작위 생성) → 서버·DB 켜기 → 헬스체크까지 한 번에 한다. 직접 하려면 `cp .env.example .env` 뒤 `docker compose up -d --build`.

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

## API

[API 안내](docs/API.md) · 명세 [`docs/api/openapi.json`](docs/api/openapi.json) · 서버를 켠 뒤 `http://127.0.0.1:8080/swagger-ui.html`

## AI 에이전트로 운영하기 (Claude 플러그인)

운영 도구(MCP 서버)와 운영 스킬 3종을 Claude 플러그인 하나로 묶었다. 플러그인은 `agent/plugin/game-ops`, MCP 서버 원본은 `agent/mcp-server` 에 있다.

```
/plugin install game-ops --marketplace ljhljh0703-cmd/game-ops-platform
```

켤 때 서버 주소와 에이전트 키(`.env` 의 `AGENT_API_KEY`)를 묻는다. 키를 비우면 조회만 된다.

| 스킬 | 하는 일 |
|---|---|
| `ops-morning` | 모든 게임의 미해결 이슈를 훑어 급한 것·잘못 묶인 것·분류가 틀린 것을 표로 제안 → 사람이 고른 것만 반영 |
| `ops-rules` | 고친 기록에서 자주 틀리는 키워드 규칙을 찾아 수정안을 만들고 평가 하네스로 전후 정확도 비교 |
| `ops-weekly` | 게임별 주간 보고 미리보기에 재발·몰린 증상을 붙여 요약 |

에이전트가 바꾼 것은 감사 기록에 `ai-agent` 로 남는다. 바꾸는 도구 3개(상태·분류·떼어내기)는 이유(`reason`)가 필수이고, 해결 처리는 제안하지 않는다(고쳤는지는 개발팀이 안다). 자세한 경계는 [운영 설계서 5장](docs/OPERATIONS.md).

## 설정 키

`.env.example` 참고. 주요 값: `DUPLICATE_THRESHOLD`(중복 기준, 기본 0.20 — 분류가 같은 이슈끼리만 비교) · `DUPLICATE_SAME_CATEGORY_ONLY`(기본 true) · `RECURRENCE_WINDOW_DAYS`(재발로 볼 기간, 기본 30일) · `LLM_*` · `SLACK_*` · `DISCORD_*` · `ADMIN_API_KEY` · `AUTHOR_HASH_SALT`.

## 게임 추가·채널 정하기

`config/games.json` 에 게임마다 한 줄씩 적는다. 저장 후 서버를 다시 켜면 반영되고, DB 게임 표도 맞춰진다(지우지는 않음 — 지난 제보 보존). 틀린 목록(같은 코드, 한 채널을 두 게임에 걸기 등)이면 서버가 켜지지 않는다.

```json
{"code": "movefu", "name": "무브퓨", "discordChannels": ["무브퓨-버그제보"], "slackChannel": "#무브퓨-알림"}
```

- 디스코드: 봇이 들어간 서버에 `무브퓨-버그제보` 채널을 만들면 그 채널 글은 무브퓨 제보가 된다. 채널 이름 대신 채널 ID 를 적어도 된다(이름을 바꿔도 안 끊기니 운영에서는 ID 권장). 그 채널 안 스레드 글도 같은 게임으로 받는다. `/report` 는 게임을 고를 수 있고, 안 고르면 쓴 채널의 게임.
- 슬랙: 게임 알림은 `slackChannel` 로, 심각한 알림은 `slackUrgentChannel` → 공용 긴급(`SLACK_CHANNEL_URGENT`) 순. 비우면 공용 채널(`SLACK_CHANNEL_DEFAULT`). 게임 채널이 없거나 봇이 못 들어가면 공용 채널로 대신 보내고 그 사실을 알림에 적는다.

## 평가 하네스

`./gradlew evalClassifier` — 정답 붙인 제보 90건으로 분류 정확도, 40쌍으로 묶기 기준값을 잰다. 규칙 v1→v3 에서 분류 정확도 dev 65→100%, 처음 보는 글(holdout) 50→83%, 한 번 틀렸던 결제 문장 16개는 회귀 묶음으로 고정. 묶기는 지금 기준이 같은 문제의 5%만 묶는다는 걸 찾았다. 자세한 것은 [docs/EVAL.md](docs/EVAL.md).

## 분류 키워드 고치기

`config/classifier-rules.json` 을 열어 고치고 저장하면 다음 제보부터 반영된다(재시작 필요 없음). 형식이 틀리거나 평가 관문(회귀 문장 전부 · dev 정확도 95% 이상)을 못 넘으면 그 파일은 받지 않고 직전 규칙을 계속 쓴다. 고치기 전후로 `./gradlew evalClassifier -Prules=수정안` 으로 재 보고, 깃에 올릴 때는 테스트가 holdout 까지 한 번 더 막는다. 들어갔는지는 `GET /api/admin/classifier/rules` 로 확인.

## 슬랙 버튼

`.env` 에 `SLACK_SIGNING_SECRET`(슬랙 앱 Basic Information > Signing Secret)과 `SLACK_ADMIN_USER_IDS`(버튼을 누를 수 있는 슬랙 멤버 ID, 쉼표로 여러 명)를 넣고 `start.sh` 를 다시 실행하면, 끝에 슬랙 버튼 주소가 나온다(`slack-request-url.txt` 에도 저장). 슬랙 앱 설정 Interactivity & Shortcuts 의 Request URL 에 넣는다. 인터넷에는 이 주소 하나만 열리고(`deploy/slack-gateway.conf`), 껐다 켜면 주소가 바뀐다. 바뀌면 서버가 1분 안에 공용 긴급 채널(`SLACK_CHANNEL_URGENT`)로 새 주소를 알린다.

## 테스트

```bash
./gradlew test
```

Docker가 있어야 한다(테스트용 PostgreSQL 컨테이너). Docker Compose 2.x 기준. 디스코드·슬랙은 가짜 이벤트와 가짜 클라이언트로 시험한다.

설계 결정과 기각한 대안은 `docs/decisions.md`, 만들면서 막힌 곳은 `docs/stuck-notes.md`.
