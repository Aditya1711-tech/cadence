import { CopyButton } from "@/components/ui/copy-button";

/** Monospace command block with a copy button. */
export function CodeBlock({ children }: { children: string }) {
  return (
    <div className="flex items-center gap-2 rounded-lg bg-slate-900 px-3 py-2 dark:bg-black">
      <code className="flex-1 overflow-x-auto whitespace-pre text-xs text-slate-100">
        {children}
      </code>
      <CopyButton value={children} />
    </div>
  );
}
