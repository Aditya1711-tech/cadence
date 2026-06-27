// Unit tests for the pure SessionTracker (P1-B.3). Run via `node --test` on the
// compiled output — no VS Code host required.

import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { ActiveContext, Segment, SessionTracker } from '../session';

const FILE_A: ActiveContext = { fileName: 'auth.ts', languageId: 'typescript', project: 'cadence-api' };
const FILE_B: ActiveContext = { fileName: 'main.go', languageId: 'go', project: 'agent' };
const NON_FILE: ActiveContext = { fileName: null, languageId: 'log', project: null };

function makeTracker(idleThresholdMs = 300_000) {
  const segments: Segment[] = [];
  const tracker = new SessionTracker({
    idleThresholdMs,
    onSegment: (s) => segments.push(s),
  });
  return { tracker, segments };
}

test('credits focused interaction time and emits one segment on flush', () => {
  const { tracker, segments } = makeTracker();
  tracker.setFocus(true, 0);
  tracker.setActiveContext(FILE_A, 0);
  tracker.recordInteraction(1_000);
  tracker.recordInteraction(20_000);
  tracker.flush(30_000);

  assert.equal(segments.length, 1);
  assert.deepEqual(segments[0].context, FILE_A);
  assert.equal(segments[0].startMs, 0);
  assert.equal(segments[0].endMs, 30_000);
  assert.equal(segments[0].reason, 'flush');
});

test('ignores interactions while the window is unfocused', () => {
  const { tracker, segments } = makeTracker();
  tracker.setActiveContext(FILE_A, 0);
  tracker.setFocus(false, 0);
  tracker.recordInteraction(5_000);
  tracker.recordInteraction(10_000);
  tracker.flush(15_000);

  assert.equal(segments.length, 0);
});

test('losing focus closes the segment at that instant', () => {
  const { tracker, segments } = makeTracker();
  tracker.setFocus(true, 0);
  tracker.setActiveContext(FILE_A, 0);
  tracker.recordInteraction(1_000);
  tracker.setFocus(false, 12_000);

  assert.equal(segments.length, 1);
  assert.equal(segments[0].endMs, 12_000);
  assert.equal(segments[0].reason, 'focus-lost');
});

test('idle tick closes the segment backdated to the last interaction', () => {
  const { tracker, segments } = makeTracker(300_000);
  tracker.setFocus(true, 0);
  tracker.setActiveContext(FILE_A, 0);
  tracker.recordInteraction(10_000); // last real interaction

  tracker.tick(200_000); // < threshold since last interaction → still open
  assert.equal(segments.length, 0);

  tracker.tick(311_000); // 301s since last interaction → idle
  assert.equal(segments.length, 1);
  assert.equal(segments[0].endMs, 10_000, 'end backdated to last interaction');
  assert.equal(segments[0].reason, 'idle');
});

test('switching files closes one segment and opens the next', () => {
  const { tracker, segments } = makeTracker();
  tracker.setFocus(true, 0);
  tracker.setActiveContext(FILE_A, 0);
  tracker.recordInteraction(1_000);
  tracker.setActiveContext(FILE_B, 5_000); // switch credits A up to 5s, opens B
  tracker.recordInteraction(9_000);
  tracker.flush(10_000);

  assert.equal(segments.length, 2);
  assert.deepEqual(segments[0].context, FILE_A);
  assert.equal(segments[0].endMs, 5_000);
  assert.equal(segments[0].reason, 'context-switch');
  assert.deepEqual(segments[1].context, FILE_B);
  assert.equal(segments[1].startMs, 5_000);
  assert.equal(segments[1].endMs, 10_000);
});

test('non-file editors are never credited', () => {
  const { tracker, segments } = makeTracker();
  tracker.setFocus(true, 0);
  tracker.setActiveContext(NON_FILE, 0);
  tracker.recordInteraction(5_000);
  tracker.flush(10_000);

  assert.equal(segments.length, 0);
});

test('pause closes the segment; resume needs a fresh interaction', () => {
  const { tracker, segments } = makeTracker();
  tracker.setFocus(true, 0);
  tracker.setActiveContext(FILE_A, 0);
  tracker.recordInteraction(1_000);
  tracker.setPaused(true, 4_000);
  assert.equal(segments.length, 1);
  assert.equal(segments[0].reason, 'paused');

  tracker.setPaused(false, 8_000); // resumed, but no interaction yet
  tracker.flush(9_000);
  assert.equal(segments.length, 1, 'no new segment without interaction after resume');

  tracker.setPaused(true, 9_000); // reset baseline (no-op close)
  tracker.setPaused(false, 9_000);
  tracker.recordInteraction(10_000);
  tracker.flush(13_000);
  assert.equal(segments.length, 2);
  assert.equal(segments[1].startMs, 10_000);
});

test('an active debug session keeps counting past the idle threshold', () => {
  const { tracker, segments } = makeTracker(300_000);
  tracker.setFocus(true, 0);
  tracker.setActiveContext(FILE_A, 0);
  tracker.recordInteraction(1_000);
  tracker.setDebugActive(true, 1_000);

  tracker.tick(400_000); // would be idle, but debug session is active
  assert.equal(segments.length, 0);

  tracker.setDebugActive(false, 400_000);
  tracker.tick(800_000); // now idle since last interaction (1s) → backdated close
  assert.equal(segments.length, 1);
  assert.equal(segments[0].endMs, 1_000);
});

test('zero-duration segments are dropped', () => {
  const { tracker, segments } = makeTracker();
  tracker.setFocus(true, 0);
  tracker.setActiveContext(FILE_A, 5_000); // opens at 5000
  tracker.flush(5_000); // end == start == 5000 → nothing credited
  assert.equal(segments.length, 0);
});
