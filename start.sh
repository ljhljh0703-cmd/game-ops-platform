#!/usr/bin/env bash
# 게임 운영 플랫폼 로컬 실행 — Docker 확인 → .env 자동 생성 → 서버·DB 켜기 → 헬스체크까지
set -euo pipefail
cd "$(dirname "$0")"
# 실행 기록을 start.log 에도 남긴다(문제가 생기면 이 파일을 보면 된다)
exec > >(tee start.log) 2>&1
echo "시작: $(date)"

echo "[1/4] Docker 확인"
if ! command -v docker >/dev/null 2>&1; then
  echo "  Docker 가 설치돼 있지 않습니다. https://www.docker.com/products/docker-desktop/ 에서 설치 후 다시 실행하세요."
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "  Docker Desktop 이 꺼져 있습니다. 앱을 켜고 상단 고래 아이콘이 멈출 때까지 기다린 뒤 다시 실행하세요."
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
ADMIN_KEY=$(grep '^ADMIN_API_KEY=' .env | cut -d= -f2-)

echo "[3/4] 서버·DB 켜기 (처음에는 내려받기 때문에 몇 분 걸립니다)"
docker compose up -d --build

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
    echo "관리자 키(상태 변경용): ${ADMIN_KEY}"
    echo "끄기:"
    echo "  docker compose down"
    exit 0
  fi
  sleep 3
done
echo "3분 안에 켜지지 않았습니다. 아래 기록을 확인하세요:"
docker compose logs --tail 50 app
exit 1
