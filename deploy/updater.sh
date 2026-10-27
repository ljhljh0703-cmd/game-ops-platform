#!/bin/sh
# 자동 업데이트(결정 46) — 깃허브 main 에 새 커밋이 올라오면 이 폴더를 그 커밋으로 맞추고 서버만 새로 만들어 켠다.
# 켜진 뒤 5분 안에 건강 검사를 통과하지 못하면 바로 전 커밋으로 되돌려 다시 켜고 슬랙에 알린다.
# 끄기: .env 에 AUTO_UPDATE=false. 확인 주기: UPDATE_INTERVAL(초, 기본 600).
# 이 폴더는 배포용이라 여기서 코드를 고치지 않는다(고친 내용은 다음 업데이트 때 덮인다). .env 같은 깃에 없는 파일은 그대로 둔다.
set -u
REPO="${UPDATE_REPO:-https://github.com/ljhljh0703-cmd/game-ops-platform.git}"
BRANCH="${UPDATE_BRANCH:-main}"
INTERVAL="${UPDATE_INTERVAL:-600}"
apk add --no-cache git >/dev/null 2>&1 || true
git config --global --add safe.directory '*'

say() { echo "$(date '+%m-%d %H:%M:%S') $*"; }
envval() { grep "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"' \r"; }
healthy() { wget -qO- -T 3 http://app:8080/actuator/health 2>/dev/null | grep -q '"UP"'; }
alert() {
  key=$(envval ADMIN_API_KEY)
  [ -n "$key" ] || return 0
  body=$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')
  wget -qO- -T 5 --header "Content-Type: application/json" --header "X-Api-Key: $key" \
    --post-data "{\"text\":\"$body\"}" http://app:8080/api/admin/ops-alert >/dev/null 2>&1 || true
}
deploy() { # 지금 체크아웃된 커밋으로 서버를 새로 만든다
  export GAMEOPS_VERSION="$(git log -1 --format='%h %s' | cut -c1-120)"
  docker compose up -d --build app >/tmp/deploy.log 2>&1
}
wait_healthy() {
  i=0
  while [ $i -lt 100 ]; do healthy && return 0; sleep 3; i=$((i+1)); done
  return 1
}

check() {
  [ "$(envval AUTO_UPDATE)" = "false" ] && return 0
  git fetch --quiet "$REPO" "$BRANCH" 2>/tmp/fetch.log || { say "확인 실패: $(tail -1 /tmp/fetch.log)"; return 0; }
  new=$(git rev-parse FETCH_HEAD)
  cur=$(git rev-parse HEAD 2>/dev/null || echo none)
  [ "$new" = "$cur" ] && return 0
  say "새 버전 $(git log -1 --format='%h %s' FETCH_HEAD)"
  git reset --quiet --hard FETCH_HEAD || { say "코드 맞추기 실패"; return 0; }
  if deploy && wait_healthy; then
    say "업데이트 끝"
    # 이 업데이터 자신이 바뀌었으면 마지막에 자신을 다시 만든다(이 줄 뒤로는 실행되지 않을 수 있다)
    if [ "$cur" != none ] && ! git diff --quiet "$cur" HEAD -- deploy/updater.sh docker-compose.yml; then
      docker compose up -d --force-recreate --no-deps updater >/dev/null 2>&1
    fi
    return 0
  fi
  say "새 버전이 켜지지 않아 되돌림"
  tail -20 /tmp/deploy.log
  if [ "$cur" != none ]; then
    git reset --quiet --hard "$cur" && deploy && wait_healthy
    alert "⚠️ 자동 업데이트 실패: $(git log -1 --format='%h %s' "$new" | cut -c1-80) 가 켜지지 않아 이전 버전으로 되돌렸어요. 새 버전은 다음 커밋이 올라올 때 다시 시도합니다."
    # 같은 실패를 10분마다 되풀이하지 않게, 다음 커밋이 올 때까지 이 커밋은 건너뛴다
    echo "$new" > /tmp/skip
  fi
}

say "자동 업데이트 시작: $REPO $BRANCH, ${INTERVAL}초마다"
sleep "${UPDATE_FIRST_DELAY:-60}"
while true; do
  if [ "$(cat /tmp/skip 2>/dev/null)" != "$(git ls-remote "$REPO" "refs/heads/$BRANCH" 2>/dev/null | cut -f1)" ]; then
    check
  fi
  sleep "$INTERVAL"
done
