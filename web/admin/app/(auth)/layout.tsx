import { type ReactNode } from "react";

/** Centered single-card shell for the unauthenticated auth pages. */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-4 py-10">
      <div className="mb-6 text-center">
        <h1 className="text-2xl font-semibold tracking-tight">Cadence</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Org admin — team health &amp; AI spend, trust-first.
        </p>
      </div>
      {children}
    </main>
  );
}
