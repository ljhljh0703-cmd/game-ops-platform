// MCP 도구 — 맞는 주소·키로 부르는지, 키가 없으면 바꾸는 도구가 막히는지, 서버 오류를 읽을 수 있는 문구로 돌려주는지
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { describe, expect, it } from "vitest";
import { GameOpsApi } from "../src/api.js";
import { createServer } from "../src/index.js";

type Call = { method: string; url: string; key: string | null; body: unknown };

function fakeFetch(routes: Record<string, { status?: number; body: unknown }>, calls: Call[]) {
  return (async (url: string, init: RequestInit) => {
    const u = new URL(url);
    const keyHeader = (init.headers as Record<string, string>)["X-Api-Key"] ?? null;
    calls.push({ method: init.method!, url: u.pathname + u.search, key: keyHeader, body: init.body ? JSON.parse(String(init.body)) : undefined });
    const r = routes[`${init.method} ${u.pathname}${u.search}`];
    if (!r) return new Response(JSON.stringify({ error: "없는 이슈" }), { status: 404 });
    return new Response(JSON.stringify(r.body), { status: r.status ?? 200 });
  }) as typeof fetch;
}

async function connect(routes: Record<string, { status?: number; body: unknown }>, apiKey?: string) {
  const calls: Call[] = [];
  const api = new GameOpsApi({ baseUrl: "http://ops.test/", apiKey, fetchImpl: fakeFetch(routes, calls) });
  const [a, b] = InMemoryTransport.createLinkedPair();
  await createServer(api).connect(a);
  const client = new Client({ name: "test", version: "0" });
  await client.connect(b);
  const call = async (name: string, args: Record<string, unknown> = {}) => {
    const r = (await client.callTool({ name, arguments: args })) as { content: { text: string }[]; isError?: boolean };
    return { text: r.content[0].text, isError: !!r.isError };
  };
  return { client, call, calls };
}

describe("game-ops MCP 도구", () => {
  it("조회 도구와 바꾸는 도구가 표시로 구분된다", async () => {
    const { client } = await connect({});
    const { tools } = await client.listTools();
    const byName = Object.fromEntries(tools.map((t) => [t.name, t]));
    expect(Object.keys(byName).sort()).toEqual([
      "change_issue_status", "classifier_rules", "corrections_summary", "detach_report",
      "get_issue", "list_games", "list_issues", "reclassify_issue", "weekly_report_preview",
    ]);
    for (const n of ["list_games", "list_issues", "get_issue", "weekly_report_preview", "corrections_summary", "classifier_rules"]) {
      expect(byName[n].annotations?.readOnlyHint).toBe(true);
    }
    for (const n of ["change_issue_status", "reclassify_issue", "detach_report"]) {
      expect(byName[n].annotations?.readOnlyHint).toBe(false);
      expect(byName[n].inputSchema.required).toContain("reason");
    }
  });

  it("게임 목록을 요약과 함께 돌려준다", async () => {
    const { call } = await connect({ "GET /api/games": { body: [{ code: "movefu", name: "무브퓨", openIssues: 2 }] } });
    const r = await call("list_games");
    expect(r.text).toContain("무브퓨(movefu) 미해결 2건");
  });

  it("상태를 바꿀 때 에이전트 키와 이유를 보낸다", async () => {
    const { call, calls } = await connect({ "PATCH /api/issues/3/status": { body: { id: 3, status: "IN_PROGRESS" } } }, "agent-key");
    const r = await call("change_issue_status", { id: 3, status: "IN_PROGRESS", reason: "결제 문제라 바로 확인" });
    expect(r.isError).toBe(false);
    expect(calls[0]).toMatchObject({ method: "PATCH", url: "/api/issues/3/status", key: "agent-key",
      body: { status: "IN_PROGRESS", reason: "결제 문제라 바로 확인" } });
  });

  it("키가 없으면 바꾸는 도구는 서버를 부르지 않고 거절한다", async () => {
    const { call, calls } = await connect({});
    const r = await call("detach_report", { issueId: 1, reportId: 2, reason: "다른 버그" });
    expect(r.isError).toBe(true);
    expect(r.text).toContain("AGENT_API_KEY");
    expect(calls).toHaveLength(0);
  });

  it("키가 없으면 이슈는 보이고 본문은 안 보인다", async () => {
    const { call, calls } = await connect({ "GET /api/issues/1": { body: { issue: { id: 1 }, reportIds: [5] } } });
    const r = await call("get_issue", { id: 1 });
    expect(r.text).toContain("본문은 볼 수 없음");
    expect(calls.map((c) => c.url)).toEqual(["/api/issues/1"]);
  });

  it("서버 오류 문구를 그대로 읽을 수 있게 돌려준다", async () => {
    const { call } = await connect({
      "POST /api/issues/1/reports/2/detach": { status: 409, body: { error: "제보가 하나뿐인 이슈는 떼어낼 수 없습니다" } },
    }, "k");
    const r = await call("detach_report", { issueId: 1, reportId: 2, reason: "다른 버그" });
    expect(r.isError).toBe(true);
    expect(r.text).toBe("409 제보가 하나뿐인 이슈는 떼어낼 수 없습니다");
  });

  it("분류 고치기는 둘 다 비면 거절, 게임 코드 형식이 틀리면 거절", async () => {
    const { call, calls } = await connect({}, "k");
    expect((await call("reclassify_issue", { id: 1, reason: "틀림" })).isError).toBe(true);
    expect((await call("list_issues", { game: "Jelly Panic" })).isError).toBe(true);
    expect(calls).toHaveLength(0);
  });

  it("서버가 꺼져 있으면 켜 보라고 안내한다", async () => {
    const api = new GameOpsApi({ baseUrl: "http://127.0.0.1:1", fetchImpl: (async () => { throw new Error("ECONNREFUSED"); }) as typeof fetch });
    const [a, b] = InMemoryTransport.createLinkedPair();
    await createServer(api).connect(a);
    const client = new Client({ name: "t", version: "0" });
    await client.connect(b);
    const r = (await client.callTool({ name: "list_games", arguments: {} })) as { content: { text: string }[]; isError?: boolean };
    expect(r.isError).toBe(true);
    expect(r.content[0].text).toContain("서버가 켜져 있는지");
  });
});
