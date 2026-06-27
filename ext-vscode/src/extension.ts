// Cadence VS Code extension — activity tracking entry point (P1-B.3–P1-B.6).
//
// This layer is intentionally thin: it observes VS Code events, derives the
// active file/language/workspace context, forwards focus / context / interaction
// / debug signals to the pure SessionTracker (session.ts), and emits the closed
// Segments to the local daemon via the DaemonEmitter (emitter.ts) on a debounce.
// Settings (cadence.enabled / redactPaths / agentPort / idleThresholdSec) and a
// pause/resume command with a status-bar toggle are wired in here (P1-B.6).

import * as path from 'path';
import * as vscode from 'vscode';
import { DEFAULT_AGENT_PORT, DaemonEmitter, EventPayload } from './emitter';
import { resolveMemberId } from './identity';
import { ActiveContext, SessionTracker } from './session';

/** How often we poll for the idle cutoff. The threshold, not this, controls sensitivity. */
const TICK_INTERVAL_MS = 15_000;
/** Debounce for sending buffered events to the daemon (also flushed on focus loss). */
const FLUSH_INTERVAL_MS = 30_000;
/** globalState key holding the offline backlog so it survives editor restarts. */
const PENDING_KEY = 'cadence.pendingEvents';

let output: vscode.OutputChannel | undefined;

export function activate(context: vscode.ExtensionContext): void {
  output = vscode.window.createOutputChannel('Cadence');
  context.subscriptions.push(output);

  const config = vscode.workspace.getConfiguration('cadence');

  // P1-B.6: a hard off switch. When disabled we track nothing and only show a
  // small status-bar hint. Changing this takes effect on the next window reload.
  if (!config.get<boolean>('enabled', true)) {
    const off = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    off.text = '$(circle-slash) Cadence off';
    off.tooltip = 'Cadence tracking is disabled (cadence.enabled = false). Enable it in Settings and reload the window.';
    off.show();
    context.subscriptions.push(off);
    output.appendLine('tracking disabled via cadence.enabled=false');
    return;
  }

  const port = config.get<number>('agentPort', DEFAULT_AGENT_PORT);
  const redactPaths = config.get<boolean>('redactPaths', true);
  const memberId = resolveMemberId(context.globalState);

  const emitter = new DaemonEmitter({
    baseUrl: `http://127.0.0.1:${port}`,
    memberId,
    redactPaths,
    log: (m) => output?.appendLine(m),
  });

  // Graceful degradation (P1-B.5): restore any backlog left by a previous
  // session (e.g. the editor was restarted while the daemon was down). Re-sends
  // are safe — the route is idempotent on event_id.
  emitter.restore(context.globalState.get<EventPayload[]>(PENDING_KEY, []));
  const persistPending = () =>
    void context.globalState.update(PENDING_KEY, emitter.snapshot());

  // A flush followed by persisting the (now smaller) backlog. Never throws.
  const flushAndPersist = async () => {
    await emitter.flush();
    persistPending();
  };

  const tracker = new SessionTracker({
    idleThresholdMs: readIdleThresholdMs(config),
    onSegment: (segment) => {
      const seconds = Math.round((segment.endMs - segment.startMs) / 1000);
      output?.appendLine(
        `segment ${seconds}s  ${segment.context.fileName ?? '(no file)'} ` +
          `[${segment.context.languageId ?? '?'}] @ ${segment.context.project ?? '(no project)'}`
      );
      emitter.enqueue(segment);
      persistPending();
    },
  });

  // P1-B.6: pause/resume with a glanceable status-bar toggle. Pausing closes the
  // open segment and flushes what was already captured; it only stops crediting
  // NEW time, never discards work.
  let paused = false;
  const statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  statusBar.command = 'cadence.toggleTracking';
  context.subscriptions.push(statusBar);
  const updateStatusBar = () => {
    statusBar.text = paused ? '$(debug-pause) Cadence paused' : '$(pulse) Cadence';
    statusBar.tooltip = paused
      ? 'Cadence tracking paused — click to resume'
      : 'Cadence tracking active — click to pause';
    statusBar.show();
  };
  const setPaused = (next: boolean) => {
    if (next === paused) {
      return;
    }
    paused = next;
    tracker.setPaused(paused, Date.now());
    if (paused) {
      void flushAndPersist(); // send what was captured before pausing
    }
    updateStatusBar();
    output?.appendLine(paused ? 'tracking paused' : 'tracking resumed');
  };
  updateStatusBar();
  context.subscriptions.push(
    vscode.commands.registerCommand('cadence.pauseTracking', () => setPaused(true)),
    vscode.commands.registerCommand('cadence.resumeTracking', () => setPaused(false)),
    vscode.commands.registerCommand('cadence.toggleTracking', () => setPaused(!paused))
  );

  // Seed initial state from the live editor + window before subscribing.
  tracker.setFocus(vscode.window.state.focused, Date.now());
  tracker.setActiveContext(contextOf(vscode.window.activeTextEditor), Date.now());

  context.subscriptions.push(
    vscode.window.onDidChangeWindowState((e) => {
      tracker.setFocus(e.focused, Date.now());
      // Flush promptly when the user leaves the window (debounce trigger).
      if (!e.focused) {
        void flushAndPersist();
      }
    }),
    vscode.window.onDidChangeActiveTextEditor((editor) =>
      tracker.setActiveContext(contextOf(editor), Date.now())
    ),
    vscode.workspace.onDidChangeTextDocument((e) => {
      if (e.document === vscode.window.activeTextEditor?.document) {
        tracker.recordInteraction(Date.now());
      }
    }),
    vscode.window.onDidChangeTextEditorSelection(() =>
      tracker.recordInteraction(Date.now())
    ),
    vscode.window.onDidChangeTextEditorVisibleRanges(() =>
      tracker.recordInteraction(Date.now())
    ),
    vscode.workspace.onDidSaveTextDocument(() =>
      tracker.recordInteraction(Date.now())
    ),
    vscode.debug.onDidChangeActiveDebugSession((session) =>
      tracker.setDebugActive(session !== undefined, Date.now())
    )
  );

  const ticker = setInterval(() => tracker.tick(Date.now()), TICK_INTERVAL_MS);
  const flusher = setInterval(() => void flushAndPersist(), FLUSH_INTERVAL_MS);
  context.subscriptions.push(
    new vscode.Disposable(() => clearInterval(ticker)),
    new vscode.Disposable(() => clearInterval(flusher)),
    new vscode.Disposable(() => {
      // Close the open segment, then best-effort final send + persist any
      // remaining backlog so it survives into the next session.
      tracker.flush(Date.now());
      persistPending();
      void flushAndPersist();
    })
  );
}

export function deactivate(): void {
  // Disposables registered in activate() flush the tracker + emitter on shutdown.
}

/**
 * Derive the tracked context from an editor. Only `file`-scheme documents are
 * trackable; untitled buffers, output panes, settings, etc. yield a non-file
 * context so the tracker does not credit them as coding.
 */
function contextOf(editor: vscode.TextEditor | undefined): ActiveContext | null {
  if (!editor) {
    return null;
  }
  const doc = editor.document;
  if (doc.uri.scheme !== 'file') {
    return { fileName: null, languageId: doc.languageId || null, project: null };
  }
  const folder = vscode.workspace.getWorkspaceFolder(doc.uri);
  return {
    fileName: path.basename(doc.fileName),
    languageId: doc.languageId || null,
    project: folder ? folder.name : null,
  };
}

function readIdleThresholdMs(config: vscode.WorkspaceConfiguration): number {
  const seconds = config.get<number>('idleThresholdSec', 300);
  return Math.max(1, seconds) * 1000;
}
