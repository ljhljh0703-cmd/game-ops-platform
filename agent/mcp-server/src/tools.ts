// MCP 도구 정의 — 조회 도구는 마음대로, 바꾸는 도구는 사람이 확인하도록 표시(annotations)하고 이유를 받는다.
// 도구 설명은 AI 가 읽는 문서다: 언제 쓰는지, 무엇이 바뀌는지, 되돌리는 법을 적는다.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { ApiError, GameOpsApi, q } from "./api.js";

const STATUS = z.enum(["OPEN", "IN_PROGRESS", "RESOLVED", "WONT_FIX"]);
const CATEGORY = z.enum(["CRASH", "BUG", "PERFORMANCE", "BALANCE", "PAYMENT", "UX", "OTHER"]);
const SEVERITY = z.enum(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);
const gameCode = z.string().regex(/^[a-z0-9][a-z0-9-]{0,39}$/).describe("게임 코드(list_games 로 확인). 예: jelly-panic, gemtower, movefu");
const issueId = z.number().int().positive().describe("이슈 번호");
const reason = z.string().min(2).max(300).describe("왜 바꾸는지 한 줄. 감사 기록에 남는다");

type Result = { content: { type: "text"; text: string }[]; isError?: boolean };

function ok(summary: string, data: unknown): Result {
  return { content: [{ type: "text", text: `${summary}\n${JSON.stringify(data, null, 2)}` }] };
}

async function run(fn: () => Promise<Result>): Promise<Result> {
  try {
    return await fn();
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : `예상하지 못한 오류: ${(e as Error).message}`;
    return { content: [{ type: "text", text: msg }], isError: true };
  }
}

const READ = { readOnlyHint: true, openWorldHint: false } as const;
const WRITE = { readOnlyHint: false, destructiveHint: false, openWorldHint: false } as const;

export function registerTools(server: McpServer, api: GameOpsApi): void {
  server.registerTool("list_games", {
    title: "게임 목록",
    description: "운영 중인 게임과 게임별 미해결(접수·진행 중) 이슈 수, 디스코드 제보 채널, 슬랙 알림 채널. 어떤 게임이 있는지 모를 때 먼저 부른다.",
    inputSchema: {},
    annotations: READ,
  }, () => run(async () => {
    const games = await api.get<{ code: string; name: string; openIssues: number }[]>("/api/games");
    return ok(games.map((g) => `${g.name}(${g.code}) 미해결 ${g.openIssues}건`).join(" · "), games);
  }));

  server.registerTool("list_issues", {
    title: "이슈 목록",
    description: "한 게임의 이슈 목록. status 를 비우면 전부. 아침 정리라면 OPEN 과 IN_PROGRESS 를 각각 본다.",
    inputSchema: { game: gameCode, status: STATUS.optional() },
    annotations: READ,
  }, ({ game, status }) => run(async () => {
    const path = `/api/issues?game=${q(game)}` + (status ? `&status=${status}` : "");
    const issues = await api.get<unknown[]>(path);
    return ok(`${game} 이슈 ${issues.length}건`, issues);
  }));

  server.registerTool("get_issue", {
    title: "이슈 자세히",
    description: "이슈 하나와, 키가 있으면 묶인 제보 본문들(유사도 포함). 잘못 묶였는지·분류가 맞는지 판단하기 전에 반드시 본문을 읽는다.",
    inputSchema: { id: issueId },
    annotations: READ,
  }, ({ id }) => run(async () => {
    const detail = await api.get<{ issue: unknown }>(`/api/issues/${id}`);
    const reports = api.hasKey ? await api.get<unknown[]>(`/api/issues/${id}/reports`, true) : "AGENT_API_KEY 가 없어 본문은 볼 수 없음";
    return ok(`이슈 #${id}`, { ...detail, reports });
  }));

  server.registerTool("weekly_report_preview", {
    title: "주간 보고 미리보기",
    description: "지난 days 일(기본 7)의 보고 문구. 슬랙으로 보내지 않는다.",
    inputSchema: { game: gameCode, days: z.number().int().min(1).max(31).default(7) },
    annotations: READ,
  }, ({ game, days }) => run(async () => {
    const r = await api.get<{ text: string }>(`/api/games/${q(game)}/weekly-report?days=${days}`);
    return { content: [{ type: "text", text: r.text }] };
  }));

  server.registerTool("corrections_summary", {
    title: "고친 기록 요약",
    description: "사람·에이전트가 자동 판단을 고친 기록의 요약. 어떤 분류가 자주 틀리는지 보고 키워드 규칙을 고칠 근거로 쓴다.",
    inputSchema: { game: gameCode },
    annotations: READ,
  }, ({ game }) => run(async () => ok(`${game} 고친 기록 요약`, await api.get(`/api/corrections/summary?game=${q(game)}`, true))));

  server.registerTool("classifier_rules", {
    title: "분류 규칙 보기",
    description: "지금 쓰는 키워드 분류 규칙(위에서부터 먼저 걸리는 하나)과 마지막 읽기 오류. 규칙 파일을 고치자고 제안하기 전에 본다.",
    inputSchema: {},
    annotations: READ,
  }, () => run(async () => ok("분류 규칙", await api.get("/api/admin/classifier/rules", true))));

  server.registerTool("change_issue_status", {
    title: "이슈 상태 바꾸기",
    description:
      "이슈 상태를 바꾼다(감사 기록에 ai-agent 로 남음). RESOLVED·WONT_FIX 는 사람이 확인한 경우에만. " +
      "되돌리려면 다시 OPEN 으로 바꾸면 되지만, 같은 내용의 열린 이슈가 이미 있으면 409 로 거절된다.",
    inputSchema: { id: issueId, status: STATUS, reason },
    annotations: { ...WRITE, idempotentHint: true },
  }, ({ id, status, reason: why }) => run(async () => {
    const r = await api.request("PATCH", `/api/issues/${id}/status`, { status, reason: why }, true);
    return ok(`#${id} → ${status} (${why})`, r);
  }));

  server.registerTool("reclassify_issue", {
    title: "분류·심각도 고치기",
    description:
      "자동 분류가 틀렸을 때 고친다. 둘 중 하나만 줘도 된다. 사람이 정한 값으로 기록되어 이후 자동 판단이 덮지 않는다. " +
      "결제(PAYMENT)·CRITICAL 로 올리거나 내리는 건 사람 확인 후에.",
    inputSchema: { id: issueId, category: CATEGORY.optional(), severity: SEVERITY.optional(), reason },
    annotations: { ...WRITE, idempotentHint: true },
  }, ({ id, category, severity, reason: why }) => run(async () => {
    if (!category && !severity) throw new ApiError(400, "category 나 severity 중 하나는 있어야 합니다");
    const r = await api.request("PATCH", `/api/issues/${id}/classification`, { category, severity, reason: why }, true);
    return ok(`#${id} 분류 고침 (${why})`, r);
  }));

  server.registerTool("detach_report", {
    title: "잘못 묶인 제보 떼어내기",
    description:
      "다른 문제인데 한 이슈로 묶인 제보를 떼어 새 이슈로 만든다(그 글만으로 다시 분류). 원래 이슈 심각도는 그대로. " +
      "제보가 하나뿐인 이슈·닫힌 이슈는 409. 되돌리기 도구는 없으니 get_issue 로 본문을 읽고 확신할 때만.",
    inputSchema: { issueId, reportId: z.number().int().positive().describe("떼어낼 제보 번호(get_issue 의 reports)"), reason },
    annotations: WRITE,
  }, ({ issueId: id, reportId, reason: why }) => run(async () => {
    const r = await api.request<{ created: { id: number } }>("POST", `/api/issues/${id}/reports/${reportId}/detach`, { reason: why }, true);
    return ok(`#${id} 에서 제보 ${reportId} 를 떼어 #${r.created.id} 로 만듦`, r);
  }));
}
