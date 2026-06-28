// RFC 7807 problem+json helpers (§6). The BFF surfaces the backend's own
// problem+json detail when it has one, and falls back to a synthesized problem
// otherwise — so the React layer always sees a consistent error shape.

import { NextResponse } from "next/server";

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
}

export function problem(status: number, title: string, detail: string): NextResponse {
  return NextResponse.json(
    { type: "about:blank", title, status, detail } satisfies Problem,
    { status, headers: { "content-type": "application/problem+json" } },
  );
}

/**
 * Convert a non-OK backend Response into a NextResponse, passing through its
 * problem+json body when present (preserving status), else a generic problem.
 */
export async function passthroughError(res: Response): Promise<NextResponse> {
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    // non-JSON error body
  }
  const p = body as Partial<Problem> | null;
  if (p && typeof p.title === "string") {
    return NextResponse.json(
      {
        type: p.type ?? "about:blank",
        title: p.title,
        status: res.status,
        detail: p.detail ?? `${res.status} ${res.statusText}`,
      } satisfies Problem,
      { status: res.status, headers: { "content-type": "application/problem+json" } },
    );
  }
  return problem(res.status, res.statusText || "Request failed", `${res.status} ${res.statusText}`);
}
