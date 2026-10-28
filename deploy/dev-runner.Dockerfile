# 개발 AI 러너(결정 49) — 서버에서 일을 받아 Codex 로 게임 저장소를 읽고(수정안) 고친다(브랜치 올리기).
# Codex 로그인은 맥의 ~/.codex 를 그대로 쓴다(ChatGPT 계정 로그인이라 건당 비용 없음)
FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends git curl jq ca-certificates ripgrep python3 \
    && rm -rf /var/lib/apt/lists/*
RUN npm install -g @openai/codex && npm cache clean --force
WORKDIR /work
COPY deploy/dev-runner.sh /usr/local/bin/dev-runner.sh
ENTRYPOINT ["bash", "/usr/local/bin/dev-runner.sh"]
