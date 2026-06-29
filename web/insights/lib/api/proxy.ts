// Thin BFF proxy helpers: forward an authenticated request to the Spring backend
// and relay its JSON (or problem+json) back to the browser.

import { NextResponse } from "next/server";
import { authedFetch, Unauthenticated } from "@/lib/api/backend";
import { passthroughError, problem } from "@/lib/api/problem";

function unauthorized(): NextResponse {
  return problem(401, "Unauthorized", "Your session has expired. Sign in again.");
}

/** Proxy a JSON-body method (POST/…) to the backend and relay the result. */
export async function proxyJson(
  path: string,
  method: string,
  body: unknown,
): Promise<NextResponse> {
  try {
    const res = await authedFetch(path, {
      method,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) return passthroughError(res);
    if (res.status === 204) return new NextResponse(null, { status: 204 });
    return NextResponse.json(await res.json());
  } catch (err) {
    if (err instanceof Unauthenticated) return unauthorized();
    throw err;
  }
}
