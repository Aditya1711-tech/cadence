"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Card } from "@/components/ui/card";
import { Field, Button, ErrorNote } from "@/components/ui/form";
import { apiPost, ApiError } from "@/lib/client";

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const next = params.get("next") || "/overview";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [orgSlug, setOrgSlug] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiPost("/api/auth/login", {
        email,
        password,
        orgSlug: orgSlug.trim() || undefined,
      });
      router.replace(next);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Sign-in failed");
      setBusy(false);
    }
  }

  return (
    <Card>
      <form onSubmit={onSubmit} className="space-y-4">
        <ErrorNote>{error}</ErrorNote>
        <Field
          label="Work email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Field
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Field
          label="Organization"
          hint="Only needed if your email belongs to more than one org."
          placeholder="org-slug (optional)"
          value={orgSlug}
          onChange={(e) => setOrgSlug(e.target.value)}
        />
        <Button type="submit" busy={busy} className="w-full">
          Sign in
        </Button>
      </form>
      <div className="mt-4 flex items-center justify-between text-sm">
        <Link href="/register" className="text-blue-600 hover:underline">
          Create an org
        </Link>
        <span className="text-slate-400">Invited? Use your invite link.</span>
      </div>
    </Card>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={<Card>Loading…</Card>}>
      <LoginForm />
    </Suspense>
  );
}
