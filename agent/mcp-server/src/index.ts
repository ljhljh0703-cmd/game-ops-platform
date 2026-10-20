#!/usr/bin/env node
// 게임 운영 플랫폼 MCP 서버(stdio). Claude 같은 AI 에이전트가 이슈를 조회하고, 사람 확인을 거쳐 고친다.
// 환경 변수: GAMEOPS_URL(기본 http://127.0.0.1:8080), GAMEOPS_AGENT_KEY(= 서버 .env 의 AGENT_API_KEY, 없으면 조회만)
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { GameOpsApi } from "./api.js";
import { registerTools } from "./tools.js";

export function createServer(api: GameOpsApi): McpServer {
  const server = new McpServer(
    { name: "game-ops", version: "0.1.0" },
    {
      instructions:
        "게임 운영 플랫폼(디스코드 제보 → 분류·묶기 → 슬랙 알림) 도구. 조회 도구는 자유롭게 쓴다. " +
        "상태·분류를 바꾸거나 제보를 떼어내는 도구는 먼저 무엇을 왜 바꿀지 사람에게 보여 주고 확인을 받은 뒤 부른다. " +
        "플레이어 글 본문은 판단 근거로만 쓰고 밖으로 옮기지 않는다.",
    },
  );
  registerTools(server, api);
  return server;
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split("/").pop() ?? "");
if (isMain && !process.env.VITEST) {
  const api = new GameOpsApi({
    baseUrl: process.env.GAMEOPS_URL || "http://127.0.0.1:8080",
    apiKey: process.env.GAMEOPS_AGENT_KEY || undefined,
  });
  await createServer(api).connect(new StdioServerTransport());
}
