// Event Contract emission to the local daemon (P1-B.4).
//
// Closed Segments from the tracker are mapped to the frozen Event Contract
// (docs/00-SYSTEM-KNOWLEDGE.md §5, mirrored in agent/internal/event/event.go),
// buffered, and POSTed to the daemon's loopback route on a debounce. The route
// (agent/internal/api) is `POST http://127.0.0.1:<port>/events`, which accepts a
// single event or an array (max 1000), is idempotent on `event_id`, and runs
// each event through the same Validate() the store uses — so every contract
// invariant below is load-bearing.
//
// mapSegment is pure so the contract mapping is unit-testable without a network
// or a VS Code host. Robust offline queueing/backoff is layered on in P1-B.5;
// this module keeps a simple in-memory buffer that survives a failed flush.

import { randomUUID } from 'crypto';
import { Segment } from './session';

/** The app name carried on every VS Code event (matches the §5 golden sample). */
export const VSCODE_APP_NAME = 'Visual Studio Code';

/** Default loopback port of the daemon (agent/cmd/cadence-agent defaultPort). */
export const DEFAULT_AGENT_PORT = 47821;

/**
 * The wire shape, snake_case per the contract. Every key is always present;
 * fields we cannot fill are `null`, never omitted. `meta` is always an object.
 */
export interface EventPayload {
  event_id: string;
  schema_ver: 1;
  source: 'vscode';
  member_id: string;
  ts_start: string; // RFC3339 UTC
  ts_end: string; // RFC3339 UTC
  duration_ms: number;
  app: string;
  title: string | null;
  url: null; // never set for vscode
  project: string | null;
  category: null; // set by the daemon's classifier, never by us
  is_idle: boolean;
  meta: { lang: string | null };
}

export interface MapOptions {
  memberId: string;
  /** When true (default), titles carry only basename + project, no path. */
  redactPaths: boolean;
  /** Injectable for deterministic tests; defaults to a fresh uuid-v4. */
  eventId?: string;
}

/**
 * Build the `title` field. We never emit an absolute path (defense in depth —
 * the daemon also redacts via its regex list). With redaction on we send
 * "<basename> — <project>"; the Segment only ever carries a basename, so the
 * redacted form is also the only form available today (full workspace-relative
 * paths are a P1-B.6 refinement).
 */
function buildTitle(segment: Segment): string | null {
  const { fileName, project } = segment.context;
  if (fileName === null) {
    return null;
  }
  return project ? `${fileName} — ${project}` : fileName;
}

/** Map a closed Segment to a contract event. Pure given its options. */
export function mapSegment(segment: Segment, opts: MapOptions): EventPayload {
  return {
    event_id: opts.eventId ?? randomUUID(),
    schema_ver: 1,
    source: 'vscode',
    member_id: opts.memberId,
    ts_start: new Date(segment.startMs).toISOString(),
    ts_end: new Date(segment.endMs).toISOString(),
    duration_ms: segment.endMs - segment.startMs,
    app: VSCODE_APP_NAME,
    title: buildTitle(segment),
    url: null,
    project: segment.context.project,
    category: null,
    is_idle: false,
    meta: { lang: segment.context.languageId },
  };
}

/** Minimal fetch surface, so we don't depend on the DOM lib for typing. */
export type FetchLike = (
  url: string,
  init: { method: string; headers: Record<string, string>; body: string }
) => Promise<{ ok: boolean; status: number }>;

export interface EmitterOptions {
  /** Base URL of the daemon, e.g. "http://127.0.0.1:47821". */
  baseUrl: string;
  memberId: string;
  redactPaths: boolean;
  /** Max events per POST; the route rejects > 1000. */
  maxBatch?: number;
  /**
   * Cap on the offline backlog. When the daemon is down for a long time we drop
   * the OLDEST events past this cap (and log it — never a silent cap) so memory
   * stays bounded. Default 5000.
   */
  maxQueue?: number;
  /** Injectable HTTP impl (defaults to global fetch). */
  fetchImpl?: FetchLike;
  /** Optional logger for flush outcomes. */
  log?: (message: string) => void;
}

/**
 * Buffers mapped events and flushes them to the daemon. A failed flush keeps the
 * events buffered so the next flush retries them (bounded by P1-B.5's queue
 * policy, which extends this). flush() never throws — emission must never break
 * the editor.
 */
export class DaemonEmitter {
  private readonly url: string;
  private readonly memberId: string;
  private readonly redactPaths: boolean;
  private readonly maxBatch: number;
  private readonly maxQueue: number;
  private readonly fetchImpl: FetchLike;
  private readonly log: (message: string) => void;
  private buffer: EventPayload[] = [];
  private droppedTotal = 0;

  constructor(opts: EmitterOptions) {
    this.url = opts.baseUrl.replace(/\/$/, '') + '/events';
    this.memberId = opts.memberId;
    this.redactPaths = opts.redactPaths;
    this.maxBatch = opts.maxBatch ?? 1000;
    this.maxQueue = opts.maxQueue ?? 5000;
    this.fetchImpl =
      opts.fetchImpl ?? ((globalThis as { fetch?: FetchLike }).fetch as FetchLike);
    this.log = opts.log ?? (() => {});
  }

  /** Map and buffer a closed segment for the next flush. */
  enqueue(segment: Segment): void {
    this.buffer.push(
      mapSegment(segment, { memberId: this.memberId, redactPaths: this.redactPaths })
    );
    this.enforceCap();
  }

  /** Number of events waiting to be sent (exposed for tests/verification). */
  pending(): number {
    return this.buffer.length;
  }

  /** Total events dropped due to the queue cap (for diagnostics). */
  dropped(): number {
    return this.droppedTotal;
  }

  /** Snapshot of pending events, for persisting across editor restarts. */
  snapshot(): EventPayload[] {
    return [...this.buffer];
  }

  /**
   * Restore previously-persisted events (oldest first), placing them ahead of
   * anything already buffered so they are sent first. Applied at startup.
   */
  restore(events: EventPayload[]): void {
    this.buffer = [...events, ...this.buffer];
    this.enforceCap();
  }

  private enforceCap(): void {
    if (this.buffer.length <= this.maxQueue) {
      return;
    }
    const drop = this.buffer.length - this.maxQueue;
    this.buffer.splice(0, drop); // drop the oldest
    this.droppedTotal += drop;
    this.log(`queue cap ${this.maxQueue} exceeded: dropped ${drop} oldest event(s) (total ${this.droppedTotal})`);
  }

  /**
   * Send up to maxBatch buffered events. On success they are dropped; on failure
   * they remain buffered for the next attempt. Returns the count sent (0 if none
   * or on failure). Never throws.
   */
  async flush(): Promise<number> {
    if (this.buffer.length === 0) {
      return 0;
    }
    const batch = this.buffer.slice(0, this.maxBatch);
    try {
      const res = await this.fetchImpl(this.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(batch),
      });
      if (!res.ok) {
        this.log(`flush: daemon returned ${res.status}; keeping ${batch.length} buffered`);
        return 0;
      }
      this.buffer = this.buffer.slice(batch.length);
      return batch.length;
    } catch (err) {
      this.log(`flush: daemon unreachable (${String(err)}); keeping ${batch.length} buffered`);
      return 0;
    }
  }
}
