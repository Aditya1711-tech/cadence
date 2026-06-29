import { type ReactNode } from "react";
import { redirect } from "next/navigation";
import { readSession } from "@/lib/api/session";
import { LogoutButton } from "@/components/shell/logout-button";

// Authenticated shell. Reads the session server-side (no flash, no token in the
// browser). The middleware already gated this route; this is defense-in-depth.
export default function AppLayout({ children }: { children: ReactNode }) {
  const s = readSession();
  if (!s) redirect("/login");

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-4xl items-center justify-between gap-4 px-4 py-3">
          <span className="text-base font-semibold tracking-tight">
            Cadence
            <span className="ml-2 text-sm font-normal text-slate-400">Ask</span>
            <span className="ml-2 text-sm font-normal text-slate-400">{s.org.name}</span>
          </span>
          <div className="flex items-center gap-3">
            <span className="hidden text-sm text-slate-500 sm:inline dark:text-slate-400">
              {s.member.display_name || s.member.email}
            </span>
            <LogoutButton />
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-4xl space-y-6 px-4 py-6">{children}</main>
    </div>
  );
}
