---
name: ops-rules
description: 분류 키워드 규칙 고치기 제안. 사람·에이전트가 자동 분류를 고친 기록을 보고 자주 틀리는 규칙을 찾아 config/classifier-rules.json 수정안을 만들고, 저장소가 열려 있으면 평가 하네스로 전후 정확도를 비교한다. 「분류 자꾸 틀려」「키워드 규칙 손보자」에 쓴다.
---

# 분류 규칙 고치기

## 순서

1. `list_games` 로 게임을 보고, 게임마다 `corrections_summary` 를 부른다. 고친 기록이 없으면 「아직 근거가 없다」고 말하고 끝낸다.
2. `classifier_rules` 로 지금 규칙을 본다. 규칙은 위에서부터 먼저 걸리는 하나를 쓴다.
3. 자주 바뀐 쌍(예: `PAYMENT → UX` 3건)마다 원인을 찾는다.
   - 너무 넓은 키워드가 위쪽 규칙에 있다(예: 「결제」가 「결제 화면 글씨가 작아요」까지 잡음)
   - 빠진 키워드가 있다
   - 규칙 순서가 틀렸다
4. 수정안을 `config/classifier-rules.json` 변경(앞뒤 비교)으로 보여 준다. 키워드를 빼는 것보다 더 구체적인 규칙을 위에 추가하는 쪽을 먼저 고려한다.
5. 저장소가 열려 있으면 수정 전후로 평가를 돌려 숫자를 붙인다.
   ```bash
   ./gradlew evalClassifier                                       # 지금 규칙
   ./gradlew evalClassifier -Prules=/tmp/classifier-rules.new.json  # 수정안
   ```
   정확도가 떨어지는 수정안은 내지 않는다. 결과는 `eval/results/` 에 남는다.
6. 사람이 좋다고 하면 파일을 저장한다. 서버는 다음 제보부터 새 규칙을 쓴다(재시작 필요 없음). `classifier_rules` 의 `lastError` 가 비었는지 확인한다.

## 지킬 것

- 결제·강제 종료 규칙의 심각도는 사람 확인 없이 낮추지 않는다.
- 평가 세트(`eval/classifier-cases.jsonl`)를 수정안에 맞춰 고치지 않는다. 정답을 바꾸려면 따로 사람 확인을 받는다.
