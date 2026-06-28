"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Card } from "@/components/ui/card";
import { Field, Button, ErrorNote } from "@/components/ui/form";
import { apiPost, ApiError } from "@/lib/client";

/** P2-E.2 onboarding step 1: a founder registers the org + first admin seat. */
export default function RegisterPage() {
  const router = useRouter();
  const [orgName, setOrgName] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiPost("/api/auth/register", {
        orgName,
        email,
        password,
        displayName: displayName.trim() || undefined,
      });
      // Land on the roster so the next step (invite the team) is right there.
      router.replace("/roster");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create org");
      setBusy(false);
    }
  }

  return (
    <Card>
      <form onSubmit={onSubmit} className="space-y-4">
        <ErrorNote>{error}</ErrorNote>
        <Field
          label="Organization name"
          required
          value={orgName}
          onChange={(e) => setOrgName(e.target.value)}
        />
        <Field
          label="Your name"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
        />
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
          autoComplete="new-password"
          required
          minLength={10}
          hint="At least 10 characters."
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" busy={busy} className="w-full">
          Create organization
        </Button>
      </form>
      <p className="mt-4 text-sm text-slate-500 dark:text-slate-400">
        Already have an account?{" "}
        <Link href="/login" className="text-blue-600 hover:underline">
          Sign in
        </Link>
      </p>
    </Card>
  );
}
