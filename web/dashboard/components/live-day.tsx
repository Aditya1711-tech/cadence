"use client";

import { useCallback, useEffect, useState } from "react";
import { computeSummary } from "@/lib/summary";
import { startOfLocalDay, endOfLocalDay } from "@/lib/time";
import type { Event } from "@/lib/contract/event";
import { DayView } from "@/components/day-view";
import { StatePanel } from "@/components/states";

// Live wrapper (P1-D.7): renders the server's initial read immediately (SSR),
// then polls /api/timeline so the dashboard stays current without a reload.
// Keeps the last good data visible while refreshing; surfaces offline/error
// with a Retry that re-hits the proxy.

const POLL_MS = 60_000;

export interface InitialDay {
  status: "ok" | "offline" | "error";
  events: Event[];
  message?: string;
}

export function LiveDay({ initial }: { initial: InitialDay }) {
  const [events, setEvents] = useState<Event[]>(initial.events);
  const [hasData, setHasData] = useState(initial.status === "ok");
  const [state, setState] = useState<"ok" | "offline" | "error">(
    initial.status,
  );
  const [message, setMessage] = useState(initial.message);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const now = new Date();
    const from = startOfLocalDay(now).toISOString();
    const to = endOfLocalDay(now).toISOString();
    setBusy(true);
    try {
      const res = await fetch(
        `/api/timeline?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
        { cache: "no-store" },
      );
      if (res.status === 503) {
        setState("offline");
        setHasData(false);
        return;
      }
      if (!res.ok) {
        let detail = `${res.status}`;
        try {
          detail = (await res.json())?.detail ?? detail;
        } catch {
          /* keep status code */
        }
        setState("error");
        setMessage(detail);
        setHasData(false);
        return;
      }
      const data = (await res.json()) as Event[];
      setEvents(data);
      setHasData(true);
      setState("ok");
    } catch {
      setState("offline");
      setHasData(false);
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    const id = setInterval(refresh, POLL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  if (!hasData) {
    return (
      <StatePanel
        kind={state === "ok" ? "empty" : state}
        message={message}
        onRetry={refresh}
        busy={busy}
      />
    );
  }

  const summary = computeSummary(events);
  if (summary.eventCount === 0) {
    return <StatePanel kind="empty" onRetry={refresh} busy={busy} />;
  }

  return (
    <div className="relative">
      {busy ? (
        <span className="pointer-events-none absolute -top-7 right-0 text-xs text-slate-400">
          updating…
        </span>
      ) : null}
      <DayView events={events} summary={summary} />
    </div>
  );
}
