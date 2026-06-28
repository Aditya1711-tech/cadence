"use client";

import { useState } from "react";
import { Card, CardTitle } from "@/components/ui/card";
import { Field, Button, ErrorNote } from "@/components/ui/form";
import { CopyButton } from "@/components/ui/copy-button";
import { formatWhen } from "@/lib/format";
import { apiPost, ApiError } from "@/lib/client";
import type { CreateInviteResponse } from "@/lib/contract/types";

type Mode = "targeted" | "open";

/** P2-E.4 — create a targeted (email) or open (shareable link) invite. */
export function InvitePanel({ onCreated }: { onCreated?: () => void }) {
  const [mode, setMode] = useState<Mode>("targeted");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("member");
  const [maxUses, setMaxUses] = useState("10");
  const [ttlHours, setTtlHours] = useState("168"); // 7 days
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<CreateInviteResponse | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const body =
        mode === "targeted"
          ? { email: email.trim(), role }
          : {
              role,
              maxUses: maxUses ? Number(maxUses) : undefined,
              ttlHours: ttlHours ? Number(ttlHours) : undefined,
            };
      const res = await apiPost<CreateInviteResponse>("/api/org/invites", body);
      setResult(res);
      onCreated?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create invite");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardTitle>Invite members</CardTitle>

      <div className="mb-4 inline-flex rounded-lg border border-slate-200 p-0.5 dark:border-slate-700">
        {(["targeted", "open"] as Mode[]).map((m) => (
          <button
            key={m}
            type="button"
            onClick={() => {
              setMode(m);
              setResult(null);
            }}
            className={
              "rounded-md px-3 py-1 text-sm font-medium transition " +
              (mode === m
                ? "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900"
                : "text-slate-600 dark:text-slate-300")
            }
          >
            {m === "targeted" ? "Email invite" : "Shareable link"}
          </button>
        ))}
      </div>

      <form onSubmit={onSubmit} className="space-y-4">
        <ErrorNote>{error}</ErrorNote>

        {mode === "targeted" ? (
          <Field
            label="Email"
            type="email"
            required
            placeholder="teammate@company.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        ) : (
          <div className="grid grid-cols-2 gap-3">
            <Field
              label="Max uses"
              type="number"
              min={1}
              value={maxUses}
              onChange={(e) => setMaxUses(e.target.value)}
            />
            <Field
              label="Expires in (hours)"
              type="number"
              min={1}
              value={ttlHours}
              onChange={(e) => setTtlHours(e.target.value)}
            />
          </div>
        )}

        <label className="block">
          <span className="mb-1 block text-sm font-medium text-slate-700 dark:text-slate-300">
            Role
          </span>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value)}
            className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm
              dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"
          >
            <option value="member">Member</option>
            <option value="admin">Admin</option>
          </select>
        </label>

        <Button type="submit" busy={busy}>
          {mode === "targeted" ? "Send invite" : "Create link"}
        </Button>
      </form>

      {result ? (
        <div className="mt-4 rounded-lg border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-950">
          <p className="mb-1 text-sm font-medium text-slate-700 dark:text-slate-200">
            Invite link
          </p>
          <div className="flex items-center gap-2">
            <code className="flex-1 truncate rounded bg-white px-2 py-1 text-xs text-slate-700 dark:bg-slate-900 dark:text-slate-300">
              {result.url}
            </code>
            <CopyButton value={result.url} />
          </div>
          <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
            Expires {formatWhen(result.expires_at)}. Share this link or have the
            invitee open it to join.
          </p>
        </div>
      ) : null}
    </Card>
  );
}
