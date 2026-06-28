"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Card } from "@/components/ui/card";
import { Field, Button, ErrorNote } from "@/components/ui/form";
import { apiGet, apiPost, ApiError } from "@/lib/client";
import type { InvitePreview } from "@/lib/contract/types";

/** P2-E.3 accept-invite: preview the invite, then set a password to join. */
export default function AcceptInvitePage({
  params,
}: {
  params: { token: string };
}) {
  const router = useRouter();
  const token = params.token;

  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiGet<InvitePreview>(`/api/auth/invite/${encodeURIComponent(token)}`)
      .then((p) => {
        setPreview(p);
        if (p.email) setEmail(p.email);
      })
      .catch((err) =>
        setLoadError(
          err instanceof ApiError ? err.message : "This invite link is invalid.",
        ),
      );
  }, [token]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiPost("/api/auth/accept", {
        token,
        password,
        displayName: displayName.trim() || undefined,
        // Targeted invites ignore this; open links require it.
        email: preview?.email ? undefined : email.trim() || undefined,
      });
      router.replace("/install");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not accept invite");
      setBusy(false);
    }
  }

  if (loadError) {
    return (
      <Card>
        <ErrorNote>{loadError}</ErrorNote>
        <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
          Ask your admin for a fresh invite link.
        </p>
      </Card>
    );
  }

  if (!preview) {
    return <Card>Loading invite…</Card>;
  }

  const targeted = Boolean(preview.email);

  return (
    <Card>
      <p className="mb-4 text-sm text-slate-600 dark:text-slate-300">
        You&apos;ve been invited to join{" "}
        <span className="font-medium text-slate-900 dark:text-slate-100">
          {preview.org_name}
        </span>
        .
      </p>
      <form onSubmit={onSubmit} className="space-y-4">
        <ErrorNote>{error}</ErrorNote>
        <Field
          label="Work email"
          type="email"
          autoComplete="email"
          required
          value={email}
          readOnly={targeted}
          hint={targeted ? "Set by your invite." : undefined}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Field
          label="Your name"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
        />
        <Field
          label="Choose a password"
          type="password"
          autoComplete="new-password"
          required
          minLength={10}
          hint="At least 10 characters."
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" busy={busy} className="w-full">
          Join {preview.org_name}
        </Button>
      </form>
    </Card>
  );
}
