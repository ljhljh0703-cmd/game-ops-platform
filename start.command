#!/usr/bin/env bash
# Finder 에서 두 번 누르면 터미널이 열리며 start.sh 를 실행한다
cd "$(dirname "$0")" && ./start.sh
echo
read -n 1 -s -r -p "아무 키나 누르면 창을 닫습니다"
