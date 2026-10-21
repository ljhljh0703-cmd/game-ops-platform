# game-ops-platform

게임 운영 플랫폼. 플레이어가 디스코드에 올린 버그·불만을 서버가 받아 분류하고, 비슷한 제보를 하나의 이슈로 묶어 DB에 넣는다. 개발팀 슬랙에는 새 이슈 카드와 주간 보고가 가고, 카드의 버튼으로 상태를 바꾸거나 분류 고치기·잘못 묶인 제보 떼어내기를 슬랙 안에서 바로 한다.

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
  슬랙 버튼·입력 창 → 서명 확인 → 관리자 확인 → 상태 변경 / 분류 고치기 / 떼어내기 + 감사·고친 기록
        │
        ▼
  어디서 바꿨든(슬랙·API·AI 에이전트) 그 이슈의 슬랙 카드를 그 자리에서 다시 그림
  해결로 바뀌면 → 디스코드 제보 글마다 「✅ 고쳤어요」 답장

  피드백 정리 → 개발 AI에게 넘기기
    슬랙 /피드백정리 (또는 Claude 「피드백 정리해」) → 「🧠 최근 피드백 정리」 보고서 카드
    카드의 「🤖 개발 AI에게 넘기기」 → 게임 저장소에 개발 지시서(깃허브 이슈, 라벨 player-feedback)
    게임 폴더 Claude·Codex 「넘어온 피드백 처리해」 → 재현 · 계획 · 사람 확인 · 고치기 · PR(고친 것만 ops-번호)
    사람이 PR 을 합치면 → 20초 안에 「🧪 QA 확인 대기」(qa=true 인 게임) · QA 가 슬랙에서 확인 → 해결 · 디스코드 제보자에게 「✅ 고쳤어요」
    카드의 「🎫 Jira 버그 티켓 만들기」 → 이슈마다 Jira Bug 티켓(재현 정보·스크린샷 첨부) → Jira 에서 완료로 옮기면 해결 · 「✅ 고쳤어요」

  재현 정보
    /report 입력칸(버전·기기·재현 순서·스크린샷) 또는 글에서 버전·기기 찾기 → 빠진 것은 접수 답장에서 묻기 → 답글로 적으면 원래 제보에 채움

  AI 에이전트(Claude 플러그인)
    고친 기록 보고 분류 규칙 수정안 → 서버가 다시 시험 → 통과한 것만 바로 반영 + 슬랙 「↩️ 되돌리기」 카드
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
| POST | `/api/admin/classifier/rules/proposals` | 규칙 수정안 → 서버가 시험해 통과하면 바로 반영(에이전트 키 가능) |
| GET · POST | `/api/admin/classifier/rules/versions` · `…/{id}/revert` | 반영한 규칙 기록, 되돌리기 |
| GET | `/api/feedback/recent?game=&days=7` | 최근 제보 본문과 묶인 이슈. 관리자·에이전트 키 |
| POST | `/api/admin/digests` | AI 피드백 정리를 슬랙 카드로 올리기 |
| POST | `/api/admin/digests/auto` | 서버가 최근 제보를 AI 로 정리해 보고서 카드로 올리기(게임·기간 선택) |
| POST | `/api/admin/digests/{id}/handoff` | 정리를 게임 저장소 깃허브 이슈(개발 지시서)로 넘기기. 같은 정리는 한 번만 |
| POST | `/slack/interactions` | 슬랙 버튼·입력 창 (서명 검사) |
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

운영 도구(MCP 서버)와 운영 스킬 5종을 Claude 플러그인 하나로 묶었다. 플러그인은 `agent/plugin/game-ops`, MCP 서버 원본은 `agent/mcp-server` 에 있다.

```
/plugin install game-ops --marketplace ljhljh0703-cmd/game-ops-platform
```

켤 때 서버 주소와 에이전트 키(`.env` 의 `AGENT_API_KEY`)를 묻는다. 키를 비우면 조회만 된다.

| 스킬 | 하는 일 |
|---|---|
| `ops-morning` | 모든 게임의 미해결 이슈를 훑어 급한 것·잘못 묶인 것·분류가 틀린 것을 표로 제안 → 사람이 고른 것만 반영 |
| `ops-digest` | 「피드백 정리해」 — 최근 제보를 모아 자주 나온 문제·새 문제·먼저 볼 것·분류가 틀려 보이는 것으로 정리해 슬랙 카드로 올리고, 원하면 개발 AI에게 넘김(게임 저장소 이슈) |
| `dev-intake` | 게임 저장소에서 「넘어온 피드백 처리해」 — 개발 지시서를 읽고 재현 → 계획 → 사람 확인 → 고치기·테스트 → 이슈에 결과 댓글. 운영 상태는 안 바꿈 |
| `ops-rules` | 고친 기록에서 자주 틀리는 키워드 규칙을 찾아 수정안을 냄 → 서버가 시험해 통과하면 바로 반영, 슬랙에서 되돌리기 |
| `ops-weekly` | 게임별 주간 보고 미리보기에 재발·몰린 증상을 붙여 요약 |

에이전트가 바꾼 것은 감사 기록에 `ai-agent` 로 남는다. 바꾸는 도구(상태·분류·떼어내기·규칙 수정)는 이유(`reason`)가 필수이고, 해결 처리는 제안하지 않는다(고쳤는지는 개발팀이 안다). 규칙 수정은 결제·크래시 키워드를 빼거나 낮추면 서버가 막는다. 자세한 경계는 [운영 설계서 5장](docs/OPERATIONS.md).

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

이슈 알림은 심각도 색 띠가 붙은 카드로 온다(긴급 빨강 · 높음 주황 · 보통 노랑 · 낮음 회색). 카드에는 분류·심각도·제보 수·상태 4칸, 플레이어 원문 인용, 재발·처음 시각, 버튼 한 줄이 있다.

| 버튼 | 하는 일 |
|---|---|
| 진행 중 · ✅ 해결 · 보류 · 다시 열기 | 상태 변경. 카드가 그 자리에서 「✅ 해결됨 · 누가 · 언제」로 바뀌고 띠가 초록이 된다 |
| 🏷️ 분류 고치기 | 입력 창에서 분류·심각도·이유를 고른다. 고친 기록에 남는다 |
| ✂️ 잘못 묶임 떼어내기 | 묶인 제보 목록에서 다른 문제인 것을 골라 새 이슈로 뺀다(제보 2건 이상일 때만 보임) |

### 홈 현황판

슬랙 왼쪽에서 봇을 누르면 홈 탭에 게임마다 열림·진행 중·긴급·이번 주 해결 수와 먼저 볼 이슈 5개, 최근 AI 피드백 정리·규칙 반영이 보인다(「🔄 새로고침」).

### /피드백정리 (슬랙 명령)

슬랙 아무 곳에서 `/피드백정리` 를 치면 최근 7일 제보를 AI 가 정리한 보고서 카드가 올라온다. 게임 알림 채널에서 치면 그 게임, 아니면 전체 게임(공용 채널). `/피드백정리 젤리패닉 3` 처럼 게임과 기간(1~31일)을 적을 수 있다. 운영 담당(`SLACK_ADMIN_USER_IDS`)만 쓸 수 있다.

```
*보스 튕김이 몰리고 결제 문제 1건*
🔥 자주 나온 문제
• #12 보스 웨이브에서 튕김 · 제보 3건 ▮▮▮▮▮ · 크래시/높음 · 진행 중
🆕 새로 생긴 문제
• #15 결제 후 보석 미지급 · 제보 1건 ▮▮▯▯▯ · 결제/긴급 · 열림
👀 먼저 볼 것
• #15 돈이 걸린 문제 · 결제/긴급 · 열림
🏷️ 분류 다시 볼 것
• #9 버그 → 불편 · 버그/낮음 · 열림
```

AI 는 고르고 요약만 하고, 숫자(제보 수·심각도·상태)는 서버가 기록에서 넣는다. AI 키(`LLM_API_KEY`)가 없거나 응답이 이상하면 기록만으로 같은 모양을 만든다.

### 개발 AI에게 넘기기

정리 카드의 「🤖 개발 AI에게 넘기기」를 누르면 그 게임 저장소(`config/games.json` 의 `repo`)에 개발 지시서 이슈가 생기고, 카드가 「🤖 개발 AI에게 넘김 · 링크 · 누가 · 언제」로 바뀐다. 지시서는 한 줄 요약 → 고칠 것(긴급·높음·결제·크래시·제보 많은 순, 이슈마다 플레이어 글 3개까지) → 완료 기준. 플레이어 글은 믿지 않는 자료라고 적고, 깃허브에서 사람 부르기(@)·이슈 연결(#)·HTML 이 동작하지 않게 바꿔 넣는다. 게임 코드는 자동으로 고치지 않는다 — 게임 폴더에서 Claude 에게 「넘어온 피드백 처리해」(`dev-intake` 스킬)라고 하면 계획을 보여 주고 확인받은 것만 고친다.

필요한 것: `.env` 의 `GITHUB_TOKEN` — 깃허브 Settings > Developer settings > Fine-grained tokens 에서 게임 저장소만 고르고 Issues: Read and write.

### 고친 코드가 들어오면 자동 해결

서버가 게임 저장소를 20초마다 보고, 합쳐진 PR(제목·본문)과 기본 브랜치 커밋 메시지에 적힌 `ops-12` 같은 운영 번호를 찾는다. 그 게임의 열린 이슈면 해결로 바꾸고(감사 기록에 `github:합친 사람`), 슬랙 카드가 초록이 되고 디스코드 제보자에게 「고쳤어요」가 간다. PR 에는 처리 결과 댓글을 단다. 다른 게임 번호·없는 번호·닫힌 이슈는 건드리지 않고, 같은 PR·커밋은 한 번만 처리한다. 최근 24시간 것만 본다. 웹훅이 필요 없어 공개 주소가 바뀌어도 그대로 돈다.

### 재현 정보 (게임 버전·기기·재현 순서·스크린샷)

QA 가 버그 제보를 받으면 먼저 묻는 네 가지를 같이 받는다. `/report` 에는 `version`·`device`·`steps`·`screenshot` 입력칸이 있고, 채널 글은 글에서 「v1.2」「버전 1.4」「아이폰 15」「크롬」 같은 낱말만 찾는다(재현 순서는 지어내지 않는다). 새 이슈면 접수 답장에 「더 알려 주시면 빨리 고쳐요: 재현 순서 · 스크린샷」처럼 빠진 것만 묻고, 플레이어가 자기 글이나 봇 답장에 답글을 달면 새 제보가 아니라 원래 제보에 채운다. 이슈 카드에는 「🧪 버전 1.2, 1.3 · 기기 아이폰 15 · 재현 순서 있음 · 스크린샷 2장」 한 줄, 개발 지시서·Jira 티켓에는 칸마다(없으면 「모름」).

### QA 확인 단계

`config/games.json` 에서 `"qa": true` 인 게임은 고친 PR 이 합쳐져도 바로 해결로 두지 않고 「🧪 QA 확인 대기」로 둔다(파란 띠). QA 가 카드의 「✅ QA 확인 완료」를 누르면 해결되고 그때 디스코드 제보자에게 「고쳤어요」가 간다. 「↩️ 아직 나요 · 다시 열기」는 다시 열림. 확인 대기 중에 같은 제보가 또 들어오면 새 이슈에 「🔁 재발 의심 · 최근 고친 #N」이 붙는다. `false` 면 합치는 순간 해결(QA 가 없는 작은 팀).

### Jira 버그 티켓

정리 카드의 「🎫 Jira 버그 티켓 만들기」를 누르면 정리에 든 열린 이슈마다 그 게임 Jira 프로젝트(`jiraProject`, 예: `GEM`)에 Bug 티켓이 하나씩 생긴다. 제목 `[게임] 이슈 제목`, 우선순위(긴급→Highest · 높음→High · 보통→Medium · 낮음→Low), 라벨 `player-feedback`·`ops-번호`·게임·분류, 본문은 재현 정보 → 플레이어 글(참고 자료 표시) → 분류 → 완료 기준. 디스코드 스크린샷은 내려받아 티켓에 직접 붙인다(디스코드 첨부 주소는 하루쯤 지나면 닫힌다). 같은 이슈는 다시 눌러도 한 장. 서버가 1분마다 티켓을 보고 완료(Done 묶음)로 옮겨졌으면 운영 이슈를 해결로 바꾸고(감사 기록 `jira:담당자 · 키`) 플레이어에게 「고쳤어요」. 이슈 카드에는 「🎫 Jira GEM-12」 링크.

필요한 것: `.env` 의 `JIRA_BASE_URL`(https://회사.atlassian.net)·`JIRA_EMAIL`·`JIRA_API_TOKEN`(https://id.atlassian.com/manage-profile/security/api-tokens), `games.json` 의 `jiraProject`. 비어 있으면 Jira 버튼이 안 보인다.

### 봇 얼굴

카드마다 게임별 봇 이름·아이콘으로 온다(`config/games.json` 의 `botName`·`icon`, 공용 카드는 「게임 운영 봇」). 아이콘은 `docs/brand/` (보석타워·운영 봇은 웹 ChatGPT 생성, 젤리 패닉은 게임 출시 아이콘, 무브퓨는 글자 아이콘).

### 슬랙 앱 설정 (처음 한 번)

1. OAuth & Permissions > Bot Token Scopes 에 `chat:write.customize` 추가(봇 얼굴용)
2. App Home > Home Tab 켜기
3. Event Subscriptions 켜기 > Request URL 에 `start.sh` 가 보여 주는 `…/slack/events` > Subscribe to bot events 에 `app_home_opened`
4. Slash Commands > Create New Command > `/피드백정리`, Request URL 에 `…/slack/commands` (권한 `commands` 가 붙는다)
5. 다시 설치(Reinstall to Workspace)
6. `.env` 에 `SLACK_CUSTOM_IDENTITY=true` 를 넣고 `start.sh` 다시 실행

플레이어 글은 슬랙 서식 문자를 바꿔 넣어 `<!channel>` 같은 전체 호출이나 가짜 링크가 동작하지 않는다. 슬랙 앱에 추가 권한은 필요 없다(chat:write 로 카드 바꾸기, 입력 창은 버튼과 같은 주소로 들어온다).

`.env` 에 `SLACK_SIGNING_SECRET`(슬랙 앱 Basic Information > Signing Secret)과 `SLACK_ADMIN_USER_IDS`(버튼을 누를 수 있는 슬랙 멤버 ID, 쉼표로 여러 명)를 넣고 `start.sh` 를 다시 실행하면, 끝에 슬랙 버튼 주소가 나온다(`slack-request-url.txt` 에도 저장). 슬랙 앱 설정 Interactivity & Shortcuts 의 Request URL 에 넣는다. 인터넷에는 이 주소 하나만 열리고(`deploy/slack-gateway.conf`), 껐다 켜면 주소가 바뀐다. 바뀌면 서버가 1분 안에 공용 긴급 채널(`SLACK_CHANNEL_URGENT`)로 새 주소를 알린다.

## 테스트

```bash
./gradlew test
```

Docker가 있어야 한다(테스트용 PostgreSQL 컨테이너). Docker Compose 2.x 기준. 디스코드·슬랙은 가짜 이벤트와 가짜 클라이언트로 시험한다.

설계 결정과 기각한 대안은 `docs/decisions.md`, 만들면서 막힌 곳은 `docs/stuck-notes.md`.
