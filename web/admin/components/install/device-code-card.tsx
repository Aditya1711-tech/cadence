"use client";

import { useState } from "react";
import { Button, Field, ErrorNote } from "@/components/ui/form";
import { CopyButton } from "@/components/ui/copy-button";
import { formatWhen } from "@/lib/format";
import { apiPost, ApiError } from "@/lib/client";
import type { DeviceCodeResponse } from "@/lib/contract/types";

/**
 * Mint a one-time device-enrollment code. The daemon exchanges this code for its
 * member identity + tokens (resolves the P1 member_id gap — see PROGRESS), so
 * every collector on the machine shares one identity.
 */
export function DeviceCodeCard() {
  const [label, setLabel] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<DeviceCodeResponse | null>(null);

  async function mint() {
    setBusy(true);
    setError(null);
    try {
      const res = await apiPost<DeviceCodeResponse>("/api/me/device-codes", {
        deviceLabel: label.trim() || undefined,
      });
      setResult(res);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create a code");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <Field
        label="Device name (optional)"
        placeholder="e.g. work-laptop"
        value={label}
        onChange={(e) => setLabel(e.target.value)}
      />
      <ErrorNote>{error}</ErrorNote>
      <Button onClick={mint} busy={busy} type="button">
        {result ? "Generate another code" : "Generate device code"}
      </Button>

      {result ? (
        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-950">
          <div className="flex items-center gap-3">
            <code className="flex-1 rounded bg-white px-3 py-2 text-center text-lg font-semibold tracking-widest text-slate-900 dark:bg-slate-900 dark:text-slate-100">
              {result.code}
            </code>
            <CopyButton value={result.code} />
          </div>
          <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
            One-time code, expires {formatWhen(result.expires_at)}. Paste it into
            the daemon when it asks to enroll this device.
          </p>
        </div>
      ) : null}
    </div>
  );
}
