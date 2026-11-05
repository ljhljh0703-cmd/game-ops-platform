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

# 맥 사용자 폴더(개발 AI 러너가 ~/.codex 를 연결한다). 이 컨테이너 안 HOME 은 맥 것이 아니라 폴더 경로에서 구한다
case "$PWD" in /Users/*) HOME=$(printf '%s' "$PWD" | cut -d/ -f1-3); export HOME ;; esac

# 기록은 화면과 배포 폴더의 .updater.log 에 같이 남긴다(맥 밖에서도 왜 멈췄는지 볼 수 있게, 끝 500줄만 유지)
say() {
  line="$(date '+%m-%d %H:%M:%S') $*"
  echo "$line"
  echo "$line" >> .updater.log 2>/dev/null && tail -500 .updater.log > .updater.log.tmp 2>/dev/null && mv .updater.log.tmp .updater.log 2>/dev/null
  return 0
}
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
# 개발 AI 러너는 서버와 따로 만든다. 러너가 안 만들어져도 서버 업데이트는 되돌리지 않고 알리기만 한다
runner() {
  docker compose config --services 2>/dev/null | grep -qx dev-runner || return 0
  if docker compose up -d --build dev-runner >/tmp/runner.log 2>&1; then
    say "개발 AI 러너 최신"
  else
    say "개발 AI 러너 만들기 실패: $(grep -iE 'error|fail' /tmp/runner.log | tail -2 | tr '\n' ' ' | cut -c1-200)"
    tail -15 /tmp/runner.log >> .updater.log 2>/dev/null
    alert "⚠️ 개발 AI 러너를 새로 만들지 못했어요(서버 업데이트는 됐어요). 다음 업데이트 때 다시 시도합니다."
  fi
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
    runner
    # 이 업데이터 자신이 바뀌었으면 마지막에 자신을 다시 만든다(이 줄 뒤로는 실행되지 않을 수 있다)
    if [ "$cur" != none ] && ! git diff --quiet "$cur" HEAD -- deploy/updater.sh docker-compose.yml; then
      docker compose up -d --force-recreate --no-deps updater >/dev/null 2>&1
    fi
    return 0
  fi
  say "새 버전이 켜지지 않아 되돌림"
  tail -20 /tmp/deploy.log
  tail -20 /tmp/deploy.log >> .updater.log 2>/dev/null
  if [ "$cur" != none ]; then
    git reset --quiet --hard "$cur" && deploy && wait_healthy
    alert "⚠️ 자동 업데이트 실패: $(git log -1 --format='%h %s' "$new" | cut -c1-80) 가 켜지지 않아 이전 버전으로 되돌렸어요. 새 버전은 다음 커밋이 올라올 때 다시 시도합니다."
    # 같은 실패를 10분마다 되풀이하지 않게, 다음 커밋이 올 때까지 이 커밋은 건너뛴다
    echo "$new" > /tmp/skip
  fi
}

say "자동 업데이트 시작: $REPO $BRANCH, ${INTERVAL}초마다"
sleep "${UPDATE_FIRST_DELAY:-60}"
# 개발 AI 러너가 없으면 켠다(이 기능 전에 켠 서버에도 업데이트만으로 들어오게)
if [ -z "$(docker compose ps -q dev-runner 2>/dev/null)" ]; then runner; fi
while true; do
  if [ "$(cat /tmp/skip 2>/dev/null)" != "$(git ls-remote "$REPO" "refs/heads/$BRANCH" 2>/dev/null | cut -f1)" ]; then
    check
  fi
  sleep "$INTERVAL"
done
