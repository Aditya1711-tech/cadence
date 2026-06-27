// Focused-interaction session tracking (P1-B.3).
//
// This module is deliberately free of any `vscode` import so the segmentation
// logic can be unit-tested without launching an editor host. The extension
// layer (extension.ts) translates VS Code events into the small method surface
// below and supplies wall-clock timestamps; all time math lives here.
//
// Model (see ext-vscode/docs/requirements-exploration.md, P1-B.1): we credit
// "real coding time" only while the window is focused, tracking is not paused,
// a real file editor is active, and the user has interacted within the idle
// threshold (or a debug session is active). This kills the "file left open
// while away" inflation. The idle threshold default (300s) and the backdating
// of an idle close to the last interaction both mirror the OS collector's
// decision in agent/docs/exploration/P1-A.2 so the two sources agree.

/** Default idle threshold, aligned with the OS collector (P1-A.2): 300s. */
export const DEFAULT_IDLE_THRESHOLD_MS = 300_000;

/** What is being worked on right now, as observed from the active editor. */
export interface ActiveContext {
  /** Basename of the active file, e.g. "auth.ts". null for non-file editors. */
  fileName: string | null;
  /** VS Code languageId, e.g. "typescript". null when not applicable. */
  languageId: string | null;
  /** Workspace-folder name owning the file, e.g. "cadence-api". null if none. */
  project: string | null;
}

/** A closed span of focused, non-idle work on a single context. */
export interface Segment {
  context: ActiveContext;
  /** Epoch ms when crediting began. */
  startMs: number;
  /** Epoch ms when crediting ended (backdated to last interaction on idle). */
  endMs: number;
  /** Why the segment closed — useful for debugging/telemetry, not the contract. */
  reason: SegmentCloseReason;
}

export type SegmentCloseReason =
  | 'focus-lost'
  | 'context-switch'
  | 'idle'
  | 'paused'
  | 'flush';

export interface SessionOptions {
  /** Idle cutoff in ms; no interaction for this long closes the segment. */
  idleThresholdMs?: number;
  /** Called once per closed segment. Segments with zero duration are dropped. */
  onSegment: (segment: Segment) => void;
}

/** True when ctx refers to a real file we should track (not a settings/output tab). */
function isTrackable(ctx: ActiveContext | null): ctx is ActiveContext {
  return ctx !== null && ctx.fileName !== null;
}

function sameContext(a: ActiveContext | null, b: ActiveContext | null): boolean {
  if (a === null || b === null) {
    return a === b;
  }
  return (
    a.fileName === b.fileName &&
    a.languageId === b.languageId &&
    a.project === b.project
  );
}

interface OpenSegment {
  context: ActiveContext;
  startMs: number;
  lastInteractionMs: number;
}

/**
 * SessionTracker accumulates focused editing time into Segments. It is purely
 * event-driven: the caller pushes focus/context/interaction/debug/pause signals
 * with explicit timestamps, and calls tick() periodically so idle spans can be
 * closed (and backdated). It owns no timers and never reads the clock itself.
 */
export class SessionTracker {
  private readonly idleThresholdMs: number;
  private readonly onSegment: (segment: Segment) => void;

  private focused = true;
  private paused = false;
  private debugActive = false;
  private context: ActiveContext | null = null;
  private open: OpenSegment | null = null;

  constructor(options: SessionOptions) {
    this.idleThresholdMs = options.idleThresholdMs ?? DEFAULT_IDLE_THRESHOLD_MS;
    this.onSegment = options.onSegment;
  }

  /** Window gained/lost OS focus. Losing focus closes the current segment. */
  setFocus(focused: boolean, nowMs: number): void {
    if (focused === this.focused) {
      return;
    }
    this.focused = focused;
    if (!focused) {
      this.close(nowMs, 'focus-lost');
    }
  }

  /**
   * The active editor changed (or there is no file editor). Switching context
   * is itself an interaction: it closes the prior segment and, when we should be
   * counting, opens a new one immediately so the switch instant is credited.
   */
  setActiveContext(context: ActiveContext | null, nowMs: number): void {
    if (sameContext(context, this.context)) {
      return;
    }
    if (this.open) {
      this.close(nowMs, 'context-switch');
    }
    this.context = context;
    if (this.shouldCount() && isTrackable(this.context)) {
      this.openAt(nowMs);
    }
  }

  /** An edit / selection move / scroll / save happened in the active editor. */
  recordInteraction(nowMs: number): void {
    if (!this.shouldCount() || !isTrackable(this.context)) {
      return;
    }
    if (this.open) {
      this.open.lastInteractionMs = nowMs;
    } else {
      this.openAt(nowMs);
    }
  }

  /** A debug session started/stopped. While active, we keep counting when idle. */
  setDebugActive(active: boolean, nowMs: number): void {
    if (active === this.debugActive) {
      return;
    }
    this.debugActive = active;
    if (active && this.shouldCount() && isTrackable(this.context) && !this.open) {
      this.openAt(nowMs);
    }
  }

  /** User paused/resumed tracking (P1-B.6 command). Pausing closes the segment. */
  setPaused(paused: boolean, nowMs: number): void {
    if (paused === this.paused) {
      return;
    }
    this.paused = paused;
    if (paused) {
      this.close(nowMs, 'paused');
    }
  }

  /**
   * Periodic check (call on an interval). If the open segment has seen no
   * interaction for the idle threshold and no debug session is active, close it
   * and backdate the end to the last interaction so the silent tail is not
   * credited as work — mirroring the OS collector's backdating (P1-A.2).
   */
  tick(nowMs: number): void {
    if (!this.open || this.debugActive) {
      return;
    }
    if (nowMs - this.open.lastInteractionMs >= this.idleThresholdMs) {
      this.close(this.open.lastInteractionMs, 'idle');
    }
  }

  /** Close any open segment (e.g. on extension deactivate). */
  flush(nowMs: number): void {
    this.close(nowMs, 'flush');
  }

  private shouldCount(): boolean {
    return this.focused && !this.paused;
  }

  private openAt(nowMs: number): void {
    // Caller guarantees this.context is trackable.
    this.open = {
      context: this.context as ActiveContext,
      startMs: nowMs,
      lastInteractionMs: nowMs,
    };
  }

  private close(endMs: number, reason: SegmentCloseReason): void {
    if (!this.open) {
      return;
    }
    const seg: Segment = {
      context: this.open.context,
      startMs: this.open.startMs,
      endMs,
      reason,
    };
    this.open = null;
    if (seg.endMs > seg.startMs) {
      this.onSegment(seg);
    }
  }
}
