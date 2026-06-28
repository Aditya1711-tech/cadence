// Browser-side helper for talking to the same-origin BFF (app/api/*). Never
// talks to the Spring backend directly — the BFF holds the tokens. Parses
// RFC 7807 problem+json into a typed error the UI can render.

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly title: string,
    detail: string,
  ) {
    super(detail || title);
    this.name = "ApiError";
  }
}

async function toError(res: Response): Promise<ApiError> {
  let title = res.statusText || "Request failed";
  let detail = `${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { title?: string; detail?: string };
    if (body.title) title = body.title;
    if (body.detail) detail = body.detail;
  } catch {
    // non-JSON error body — keep the status line
  }
  return new ApiError(res.status, title, detail);
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(path, {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) throw await toError(res);
  return (await res.json()) as T;
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: "POST",
    headers: { Accept: "application/json", "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  if (!res.ok) throw await toError(res);
  // 204 No Content → no body to parse
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
