#!/usr/bin/env bash
# 개발 AI 러너(결정 49) — 15초마다 서버에 일을 묻는다.
#   PLAN: 게임 저장소를 받아 Codex 가 코드를 읽고 수정안 JSON 을 쓴다(파일을 바꿔도 버린다)
#   FIX : 승인한 안대로 Codex 가 고치고, 바뀐 것이 있으면 ops/fix-… 브랜치로 올린다. PR 은 서버가 연다
# 비밀값(깃허브 토큰·관리자 키)은 Codex 에게 넘기지 않는다: 환경 변수를 빼고 돌리고, 깃 인증은 저장소 설정에 남기지 않는다
set -u
API="${OPS_API:-http://app:8080}"
KEY="${ADMIN_API_KEY:-}"
GH="${GITHUB_TOKEN:-}"
CODEX_HOME="${CODEX_HOME:-/codex}"   # 러너 전용(맥 Codex 설정·연결 도구는 안 불러온다)
HOST_CODEX="${HOST_CODEX:-/host-codex}" # 맥의 ~/.codex — 로그인(auth.json)만 빌려 쓴다
TIMEOUT="${DEV_RUNNER_TIMEOUT:-2400}"
WORK="${DEV_RUNNER_WORK:-/work}"
GIT_BASE="${GIT_BASE:-https://github.com}"
export CODEX_HOME

say() { echo "$(date '+%m-%d %H:%M:%S') $*"; }
mkdir -p "$CODEX_HOME"
# 로그인 빌려 쓰기: 일마다 맥 것을 복사해 오고, Codex 가 로그인을 새로 고쳤으면(그새 맥 쪽이 안 바뀐 경우만) 맥에 돌려놓는다.
# 돌려놓지 않으면 맥 Codex 가 옛 로그인으로 남아 로그아웃될 수 있다
auth_in() {
  [ -s "$HOST_CODEX/auth.json" ] || return 1
  cp "$HOST_CODEX/auth.json" "$CODEX_HOME/auth.json" && AUTH_SUM=$(md5sum < "$HOST_CODEX/auth.json")
}
auth_out() {
  [ -s "$CODEX_HOME/auth.json" ] || return 0
  cmp -s "$CODEX_HOME/auth.json" "$HOST_CODEX/auth.json" && return 0
  [ "$(md5sum < "$HOST_CODEX/auth.json")" = "$AUTH_SUM" ] || return 0
  cp "$CODEX_HOME/auth.json" "$HOST_CODEX/.auth.json.runner" && mv "$HOST_CODEX/.auth.json.runner" "$HOST_CODEX/auth.json" && say "Codex 로그인 갱신을 맥에 반영"
}
auth_header() { printf 'AUTHORIZATION: basic %s' "$(printf 'x-access-token:%s' "$GH" | base64 -w0)"; }
git_auth() { git -c http.https://github.com/.extraheader="$(auth_header)" "$@"; }

report() { # id json
  curl -fsS -m 30 -X POST -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
    --data-binary @- "$API/api/admin/dev-jobs/$1/result" >/dev/null <<<"$2" || say "결과 보내기 실패 #$1"
}
fail() { report "$1" "$(jq -n --arg e "$2" '{ok:false,error:$e}')"; say "실패 #$1: $2"; }

[ -n "$KEY" ] || { say "ADMIN_API_KEY 가 없어 쉰다"; sleep infinity; }
say "개발 AI 러너 시작 (Codex $(codex --version 2>/dev/null | tail -1))"

while :; do
  job=$(curl -fsS -m 20 -X POST -H "X-Api-Key: $KEY" "$API/api/admin/dev-jobs/claim" 2>/dev/null || true)
  if [ -z "$job" ]; then sleep 15; continue; fi
  id=$(jq -r .id <<<"$job"); kind=$(jq -r .kind <<<"$job"); repo=$(jq -r .repo <<<"$job")
  branch=$(jq -r '.branch // empty' <<<"$job"); title=$(jq -r '.commitTitle // empty' <<<"$job")
  say "일 #$id $kind $repo"

  if ! auth_in; then
    fail "$id" "맥에서 Codex 로그인 파일을 못 찾았어요. 맥 터미널에서 codex login 을 한 번 하고 「다시 시도」를 눌러 주세요"; continue
  fi
  if [ -z "$GH" ]; then fail "$id" "깃허브 토큰이 없어요(.env 의 GITHUB_TOKEN)"; continue; fi
  if ! [[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then fail "$id" "저장소 이름 형식 오류: $repo"; continue; fi

  dir="$WORK/job-$id"; rm -rf "$dir"
  if ! git_auth clone --quiet --depth 50 "$GIT_BASE/$repo.git" "$dir" 2>/tmp/clone.log; then
    fail "$id" "저장소를 받지 못했어요: $(tail -1 /tmp/clone.log)"; continue
  fi
  cd "$dir" || continue
  [ "$kind" = FIX ] && git checkout --quiet -b "$branch"
  jq -r .prompt <<<"$job" > "$WORK/prompt-$id.txt"
  out="$WORK/out-$id.txt"; : > "$out"
  # 비밀값을 뺀 환경에서 돌린다. 컨테이너가 울타리라 Codex 자체 격리는 끈다
  env -u GITHUB_TOKEN -u ADMIN_API_KEY -u GH -u KEY timeout "$TIMEOUT" \
    codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -C "$dir" -o "$out" - \
    < "$WORK/prompt-$id.txt" > "$WORK/log-$id.txt" 2>&1
  rc=$?
  auth_out
  cd "$WORK" || true
  if [ $rc -ne 0 ] || [ ! -s "$out" ]; then
    why=$(grep -iE "error|login|auth|limit" "$WORK/log-$id.txt" | tail -2 | tr '\n' ' ' | cut -c1-300)
    [ $rc -eq 124 ] && why="$((TIMEOUT/60))분 안에 끝나지 않았어요"
    fail "$id" "개발 AI(Codex)가 실패했어요(코드 $rc). ${why:-로그: deploy 폴더 밖 컨테이너 기록}"
    rm -rf "$dir"; continue
  fi

  if [ "$kind" = PLAN ]; then
    report "$id" "$(jq -n --rawfile o "$out" '{ok:true,output:$o}')"
    say "수정안 보냄 #$id"
  else
    git -C "$dir" add -A
    if git -C "$dir" diff --cached --quiet; then
      report "$id" "$(jq -n --rawfile o "$out" '{ok:true,output:$o,branch:null,files:[]}')"
    else
      files=$(git -C "$dir" diff --cached --name-only | jq -R . | jq -s .)
      git -C "$dir" -c user.name="game-ops dev AI" -c user.email="dev-ai@game-ops.local" commit --quiet -m "$title" \
        -m "$(grep -E '^FIXED:' "$out" | tail -1)"
      if git_auth -C "$dir" push --quiet --force origin "$branch" 2>/tmp/push.log; then
        report "$id" "$(jq -n --rawfile o "$out" --arg b "$branch" --argjson f "$files" '{ok:true,output:$o,branch:$b,files:$f}')"
        say "고친 브랜치 올림 #$id $branch"
      else
        fail "$id" "브랜치를 올리지 못했어요(깃허브 토큰에 Contents 쓰기 권한 필요): $(tail -1 /tmp/push.log | cut -c1-200)"
      fi
    fi
  fi
  rm -rf "$dir" "$WORK/prompt-$id.txt"
done
