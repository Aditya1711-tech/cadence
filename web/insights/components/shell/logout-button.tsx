"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { apiPost } from "@/lib/client";

export function LogoutButton() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function logout() {
    setBusy(true);
    try {
      await apiPost("/api/auth/logout");
    } catch {
      // ignore — cookie is cleared server-side best-effort
    }
    router.replace("/login");
  }
  return (
    <button
      onClick={logout}
      disabled={busy}
      className="rounded-lg px-3 py-1.5 text-sm font-medium text-slate-500
        hover:bg-slate-100 hover:text-slate-700 disabled:opacity-60
        dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-200"
    >
      {busy ? "Signing out…" : "Sign out"}
    </button>
  );
}
