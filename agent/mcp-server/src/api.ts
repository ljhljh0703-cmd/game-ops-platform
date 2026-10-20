// 게임 운영 플랫폼 REST API 를 부르는 얇은 층 — 주소·키·오류 문구만 책임진다
export type Fetch = typeof fetch;

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export interface ApiOptions {
  baseUrl: string;
  /** AI 에이전트 전용 키(AGENT_API_KEY). 없으면 조회만 된다. */
  apiKey?: string;
  fetchImpl?: Fetch;
  timeoutMs?: number;
}

export class GameOpsApi {
  private readonly baseUrl: string;
  private readonly fetchImpl: Fetch;

  constructor(private readonly opts: ApiOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/+$/, "");
    this.fetchImpl = opts.fetchImpl ?? fetch;
  }

  get hasKey(): boolean {
    return !!this.opts.apiKey;
  }

  async request<T>(method: string, path: string, body?: unknown, needsKey = false): Promise<T> {
    if (needsKey && !this.opts.apiKey) {
      throw new ApiError(403, "AGENT_API_KEY 가 설정되지 않아 이 작업을 할 수 없습니다(조회만 가능).");
    }
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (this.opts.apiKey) headers["X-Api-Key"] = this.opts.apiKey;
    let res: Response;
    try {
      res = await this.fetchImpl(this.baseUrl + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(this.opts.timeoutMs ?? 10_000),
      });
    } catch (e) {
      throw new ApiError(0, `운영 서버(${this.baseUrl})에 연결할 수 없습니다. 서버가 켜져 있는지 확인하세요. (${(e as Error).message})`);
    }
    const text = await res.text();
    let data: unknown = undefined;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = text;
      }
    }
    if (!res.ok) {
      const reason = typeof data === "object" && data && "error" in data ? String((data as { error: unknown }).error) : text;
      throw new ApiError(res.status, `${res.status} ${reason || res.statusText}`);
    }
    return data as T;
  }

  get<T>(path: string, needsKey = false) {
    return this.request<T>("GET", path, undefined, needsKey);
  }
}

export const q = encodeURIComponent;
