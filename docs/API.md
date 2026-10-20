# 게임 운영 플랫폼 API 안내

플레이어 제보는 분류된 뒤 비슷한 것끼리 묶여 **이슈**가 된다. 이 API로 이슈를 조회하고, 사람이나 AI 에이전트가 이슈 상태와 자동 판단을 고친다.

- 명세 원본: [`docs/api/openapi.json`](api/openapi.json). 테스트가 코드와 대조하므로 코드와 어긋나지 않는다.
- 서버를 켠 뒤 브라우저로 볼 때: `http://127.0.0.1:8080/swagger-ui.html`
- 서버는 내 컴퓨터 안(`127.0.0.1`)에서만 열린다. 인터넷에는 슬랙 버튼 주소 하나만 열린다(이 안내에는 없음).

## 1. 5분 안에 써 보기

```bash
# 1) 어떤 게임이 있고 미해결 이슈가 몇 건인가
curl -s http://127.0.0.1:8080/api/games

# 2) 제보 하나 넣기 (디스코드 대신 직접)
curl -s -X POST http://127.0.0.1:8080/api/reports \
  -H 'Content-Type: application/json' \
  -d '{"game":"movefu","authorId":"web-1","body":"점프하면 벽에 끼어요"}'
# → {"reportId":1,"issueId":1,"outcome":"NEW_ISSUE","similarity":0.0}

# 3) 그 게임의 이슈 목록
curl -s 'http://127.0.0.1:8080/api/issues?game=movefu'

# 4) 해결로 바꾸기 (관리자 키 필요)
curl -s -X PATCH http://127.0.0.1:8080/api/issues/1/status \
  -H 'Content-Type: application/json' -H "X-Api-Key: $ADMIN_API_KEY" \
  -d '{"status":"RESOLVED"}'
```

## 2. 인증: 누가 바꿨는지 남기기

조회와 제보 등록은 키 없이 된다. **바꾸는 요청**과 **플레이어 글 본문을 보는 요청**은 `X-Api-Key` 헤더가 있어야 한다.

| 키 (.env) | 감사 기록에 남는 이름 | 쓰는 쪽 |
|---|---|---|
| `ADMIN_API_KEY` | `local-admin` | 사람(운영자) |
| `AGENT_API_KEY` | `ai-agent` | AI 에이전트(MCP 서버) |

키를 따로 두는 이유는 세 가지다. 감사 기록만 보고도 사람이 고친 것과 에이전트가 고친 것을 구분할 수 있다. 에이전트가 잘못 움직이면 `AGENT_API_KEY` 만 비워서 바로 끊을 수 있다. 에이전트 키로는 이슈를 해결(`RESOLVED`)·보류(`WONT_FIX`)로 닫을 수 없다(403). 고쳤는지는 개발팀만 알고, 플레이어 글에 숨은 지시로 이슈가 닫히는 일도 막는다. 키가 비어 있으면 아무도 통과하지 못한다.

## 3. 주소 목록

| 할 일 | 요청 | 키 |
|---|---|---|
| 게임 목록·미해결 수 | `GET /api/games` | — |
| 주간 보고 미리보기(보내지 않음) | `GET /api/games/{code}/weekly-report?days=7` | — |
| 제보 등록 | `POST /api/reports` | — |
| 이슈 목록 | `GET /api/issues?game={code}&status={상태}` | — |
| 이슈 하나 | `GET /api/issues/{id}` | — |
| 이슈에 묶인 제보 본문 | `GET /api/issues/{id}/reports` | 필요 |
| 상태 바꾸기 | `PATCH /api/issues/{id}/status` | 필요 |
| 분류·심각도 고치기 | `PATCH /api/issues/{id}/classification` | 필요 |
| 잘못 묶인 제보 떼어내기 | `POST /api/issues/{id}/reports/{reportId}/detach` | 필요 |
| 고친 기록 / 요약 | `GET /api/corrections?game=` / `GET /api/corrections/summary?game=` | 필요 |
| 지금 쓰는 분류 규칙 | `GET /api/admin/classifier/rules` | 필요 |

## 4. 값의 뜻

| 항목 | 값 |
|---|---|
| 상태 `status` | `OPEN` 접수 · `IN_PROGRESS` 진행 중 · `RESOLVED` 해결 · `WONT_FIX` 고치지 않기로 함 |
| 분류 `category` | `CRASH` 튕김 · `BUG` 버그 · `PERFORMANCE` 성능 · `BALANCE` 밸런스 · `PAYMENT` 결제 · `UX` 불편 · `OTHER` 기타 |
| 심각도 `severity` | `LOW` · `MEDIUM` · `HIGH` · `CRITICAL` (HIGH 이상은 슬랙 긴급 채널) |
| 접수 결과 `outcome` | `NEW_ISSUE` 새 이슈(201) · `ATTACHED_TO_EXISTING` 비슷한 이슈에 붙음(200) · `DUPLICATE_DELIVERY` 같은 `externalId` 재전송(200) |
| `classifiedBy` | `rules`(키워드 규칙) · `llm`(AI 분류) · `human`(사람이 고침) |

## 5. 오류

오류는 모두 `{"error": "사람이 읽을 이유"}` 형태로 온다.

| 코드 | 언제 | 어떻게 |
|---|---|---|
| 400 | 값이 빠졌거나 범위 밖(예: `days=99`) | `error` 문구대로 고쳐 다시 |
| 403 | 키가 없거나 틀림, 또는 에이전트 키로 해결·보류 시도 | `.env` 의 키 확인 / 사람이 처리 |
| 404 | 없는 이슈·게임 | 번호·게임 코드 확인 |
| 409 | 지금 상태에서 못 하는 요청(제보가 하나뿐인 이슈에서 떼어내기, 같은 내용의 열린 이슈가 있는데 다시 열기, 동시에 고침) | 새로 조회한 뒤 다시 |

## 6. 지켜지는 규칙 (API 를 쓰는 쪽이 알아야 할 것)

- **사람 판단을 자동이 덮지 않는다.** 사람이 심각도를 내린 이슈에 「튕겨요」 제보가 붙어도 심각도는 그대로다.
- **떼어내기는 되돌리기 쉬운 쪽으로.** 떼어낸 제보는 그 글만으로 다시 분류해 새 이슈가 되고, 원래 이슈의 심각도는 내려가지 않는다.
- **같은 제보는 한 번만.** 같은 `externalId` 로 다시 보내면 새로 저장하지 않고 원래 이슈 번호를 돌려준다. 재시도해도 안전하다.
- **게임이 다르면 묶지 않는다.** 같은 문장이어도 게임이 다르면 이슈가 따로 생긴다.

## 7. 명세가 바뀔 때

API 를 바꾸면 `OpenApiDocTest` 가 실패한다. 의도한 변경이면 `./gradlew test -DupdateOpenApi=true` 로 `openapi.json` 을 새로 쓰고, 이 안내도 함께 고친다. 둘이 같은 커밋에 들어가야 한다.
