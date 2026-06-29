import { NextRequest } from "next/server";
import { rawBackend } from "@/lib/api/backend";
import { completeAuth } from "@/lib/api/auth-bff";
import { problem } from "@/lib/api/problem";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return problem(400, "Bad request", "expected a JSON body");
  }
  const res = await rawBackend("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify(body),
  });
  return completeAuth(res);
}
