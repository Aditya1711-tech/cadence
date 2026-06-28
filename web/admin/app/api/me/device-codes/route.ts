import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/api/proxy";

export const dynamic = "force-dynamic";

/**
 * Mint a one-time device-enrollment code for the caller's daemon. The backend
 * takes deviceLabel as a query param (not a body), so we forward it as one.
 */
export async function POST(req: NextRequest) {
  let label: string | undefined;
  try {
    const body = (await req.json()) as { deviceLabel?: string };
    label = body?.deviceLabel?.trim() || undefined;
  } catch {
    // no body — fine, label is optional
  }
  const qs = label ? `?deviceLabel=${encodeURIComponent(label)}` : "";
  return proxyJson(`/api/v1/me/device-codes${qs}`, "POST", undefined);
}
