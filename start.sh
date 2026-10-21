#!/usr/bin/env bash
# 게임 운영 플랫폼 로컬 실행 — Docker 확인 → .env 자동 생성 → 서버·DB 켜기 → 헬스체크까지
set -euo pipefail
cd "$(dirname "$0")"
# 실행 기록을 start.log 에도 남긴다(문제가 생기면 이 파일을 보면 된다)
exec > >(tee start.log) 2>&1
echo "시작: $(date)"

echo "[1/4] Docker 확인 (OrbStack 또는 Docker Desktop)"
if ! command -v docker >/dev/null 2>&1; then
  echo "  docker 명령이 없습니다. OrbStack(https://orbstack.dev) 을 설치하고 한 번 켠 뒤 다시 실행하세요."
  exit 1
fi
# Docker 가 멈춰 있으면 docker info 가 끝없이 기다린다(2026-10-20 실제로 있었음). 20초 안에 답이 없으면 멈춘 것으로 본다.
docker info >/dev/null 2>&1 &
info_pid=$!
docker_ok=""
for _ in $(seq 1 20); do
  if ! kill -0 "$info_pid" 2>/dev/null; then
    wait "$info_pid" && docker_ok=1
    break
  fi
  sleep 1
done
if [ -z "$docker_ok" ]; then
  kill "$info_pid" 2>/dev/null || true
  echo "  Docker 가 꺼져 있거나 응답하지 않습니다."
  echo "  OrbStack(또는 Docker Desktop)을 켜고 1분쯤 기다린 뒤 다시 실행하세요. 계속 멈추면 그 앱을 완전히 끄고 다시 켜세요."
  exit 1
fi

echo "[2/4] 설정 파일(.env) 확인"
if [ ! -f .env ]; then
  cp .env.example .env
  salt=$(openssl rand -hex 24)
  key=$(openssl rand -hex 24)
  sed -i.bak "s|^AUTHOR_HASH_SALT=.*|AUTHOR_HASH_SALT=${salt}|; s|^ADMIN_API_KEY=.*|ADMIN_API_KEY=${key}|" .env && rm -f .env.bak
  echo "  .env 를 새로 만들고 관리자 키를 넣었습니다."
else
  echo "  기존 .env 를 그대로 씁니다."
fi
# 값에 따옴표를 둘러 써도 Docker 와 같은 뜻으로 읽는다
SLACK_ON=$( (grep '^SLACK_ENABLED=' .env || true) | tail -1 | cut -d= -f2- | tr -d "[:space:]\"'")
SLACK_SECRET_LEN=$( (grep '^SLACK_SIGNING_SECRET=' .env || true) | tail -1 | cut -d= -f2- | tr -d "[:space:]\"'" | wc -c | tr -d ' ')
# macOS 기본 bash(3.2)에서도 돌도록 배열 대신 문자열을 쓴다
SLACK_APP=$( (grep '^SLACK_APP_TOKEN=' .env || true) | tail -1 | cut -d= -f2- | tr -d "[:space:]\"'")
PROFILE=""
SOCKET=""
if [ "${SLACK_ON}" = "true" ] && [ "${SLACK_APP#xapp-}" != "${SLACK_APP}" ]; then
  # 소켓 모드(결정 40): 서버가 슬랙에 먼저 연결한다. 공개 주소·중계·터널이 필요 없고 슬랙 주소를 바꿀 일이 없다
  SOCKET="yes"
  echo "  슬랙은 소켓 모드로 연결합니다(공개 주소 없음, 슬랙 주소 설정 필요 없음)."
elif [ "${SLACK_ON}" = "true" ] && [ "${SLACK_SECRET_LEN}" -gt 0 ]; then
  PROFILE="--profile slack-buttons"
  echo "  슬랙 버튼용 공개 주소도 함께 켭니다."
fi

# 흔한 설정 실수를 켜기 전에 먼저 알려 준다
SLACK_TOKEN=$( (grep '^SLACK_BOT_TOKEN=' .env || true) | tail -1 | cut -d= -f2- | tr -d "[:space:]\"'")
if [ "${SLACK_ON}" = "true" ] && [ "${SLACK_TOKEN#xoxb-}" = "${SLACK_TOKEN}" ]; then
  echo "  주의: SLACK_BOT_TOKEN 이 xoxb- 로 시작하지 않습니다. 슬랙 앱 설정 > OAuth & Permissions 의 Bot User OAuth Token 을 넣어야 알림이 갑니다."
fi
case "$PWD" in
  "$HOME/Downloads"*|"$HOME/Documents"*|"$HOME/Desktop"*)
    echo "  참고: 이 폴더는 맥이 따로 지키는 위치입니다. 처음 켤 때 Docker 가 이 폴더 접근을 물으면 허용해야 합니다."
    echo "        서버가 「Starting」에서 몇 분째 멈춰 있으면 시스템 설정 > 개인정보 보호 및 보안 > 파일 및 폴더 > Docker 를 확인하세요." ;;
esac

echo "[3/4] 서버·DB 켜기 (처음에는 내려받기 때문에 몇 분 걸립니다)"
# shellcheck disable=SC2086
docker compose $PROFILE up -d --build
if [ -n "$SOCKET" ]; then
  # 예전 방식으로 켜 둔 중계·터널이 남아 있으면 끈다(쓸 일이 없다)
  docker compose --profile slack-buttons stop slack-gateway tunnel >/dev/null 2>&1 || true
fi
if [ -n "$PROFILE" ]; then
  # 슬랙 관문의 주소 규칙 파일이 바뀌어도 관문이 이미 켜져 있으면 옛 규칙을 계속 쓴다(2026-10-21 홈 현황판 주소가 404). 매번 다시 읽게 한다.
  # shellcheck disable=SC2086
  docker compose $PROFILE restart slack-gateway >/dev/null
fi

echo "[4/4] 서버가 뜰 때까지 기다리는 중"
for i in $(seq 1 60); do
  if curl -fs localhost:8080/actuator/health >/dev/null 2>&1; then
    echo
    echo "켜졌습니다: $(curl -s localhost:8080/actuator/health)"
    echo
    echo "제보 하나 올려 보기:"
    echo "  curl -H 'Content-Type: application/json' -d '{\"game\":\"gemtower\",\"authorId\":\"me\",\"body\":\"보스 웨이브에서 튕겨요\"}' localhost:8080/api/reports"
    echo "이슈 목록 보기:"
    echo "  curl 'localhost:8080/api/issues?game=gemtower'"
    echo "관리자 키(상태 변경용): .env 의 ADMIN_API_KEY (기록에 남지 않게 화면에는 안 씁니다)"
    if [ -n "$PROFILE" ]; then
      url=""
      rm -f slack-request-url.txt
      for j in $(seq 1 20); do
        # 임시 주소는 「단어-단어-…」 모양이다. 실패 기록에 나오는 api.trycloudflare.com 은 거른다.
        if [ -n "$(docker compose ps --status running -q tunnel 2>/dev/null)" ]; then
          url=$(docker compose logs tunnel 2>/dev/null | grep -oE 'https://[a-z0-9]+(-[a-z0-9]+)+\.trycloudflare\.com' | tail -1 || true)
        fi
        [ -n "$url" ] && break
        sleep 2
      done
      if [ -n "$url" ]; then
        echo "${url}/slack/interactions" > slack-request-url.txt
        echo
        echo "슬랙 버튼 주소: ${url}/slack/interactions"
        echo "  슬랙 앱 설정 > Interactivity & Shortcuts > Request URL 에 넣으세요. 공개 주소는 다시 켤 때마다 바뀝니다."
        echo "슬랙 홈 현황판 주소: ${url}/slack/events"
        echo "  슬랙 앱 설정 > Event Subscriptions > Request URL 에 넣으세요(바뀌면 슬랙 긴급 채널로도 알려 줍니다)."
        echo "슬랙 명령(/피드백정리) 주소: ${url}/slack/commands"
        echo "  슬랙 앱 설정 > Slash Commands > /피드백정리 > Request URL 에 넣으세요."
      else
        echo "슬랙 버튼용 공개 주소를 받지 못했습니다: docker compose logs tunnel"
      fi
    elif [ -n "$SOCKET" ]; then
      echo "슬랙: 소켓 모드. 버튼·홈 현황판·/피드백정리가 주소 설정 없이 돌아갑니다."
    elif [ "${SLACK_ON}" = "true" ]; then
      echo "슬랙 버튼을 쓰려면 .env 의 SLACK_SIGNING_SECRET 을 채우고 다시 실행하세요(알림은 지금도 갑니다)."
    fi
    echo "끄기:"
    echo "  docker compose --profile slack-buttons down"
    exit 0
  fi
  sleep 3
done
echo "3분 안에 켜지지 않았습니다. 아래 기록을 확인하세요:"
docker compose logs --tail 50 app
exit 1
