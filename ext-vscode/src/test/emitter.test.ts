// Tests for the Event Contract mapping + buffered emitter (P1-B.4). Pure /
// fake-fetch only — no network, no VS Code host.

import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { DaemonEmitter, FetchLike, VSCODE_APP_NAME, mapSegment } from '../emitter';
import { Segment } from '../session';

function seg(over: Partial<Segment> = {}): Segment {
  return {
    context: { fileName: 'auth.ts', languageId: 'typescript', project: 'cadence-api' },
    startMs: Date.parse('2025-06-01T09:14:02.000Z'),
    endMs: Date.parse('2025-06-01T09:18:45.000Z'),
    reason: 'flush',
    ...over,
  };
}

const MAP_OPTS = { memberId: 'member-1', redactPaths: true, eventId: 'evt-1' };

test('mapSegment produces the frozen contract shape', () => {
  const e = mapSegment(seg(), MAP_OPTS);
  // Every contract key present, in-spec values.
  assert.equal(e.event_id, 'evt-1');
  assert.equal(e.schema_ver, 1);
  assert.equal(e.source, 'vscode');
  assert.equal(e.member_id, 'member-1');
  assert.equal(e.ts_start, '2025-06-01T09:14:02.000Z');
  assert.equal(e.ts_end, '2025-06-01T09:18:45.000Z');
  assert.equal(e.duration_ms, 283_000);
  assert.equal(e.app, VSCODE_APP_NAME);
  assert.equal(e.title, 'auth.ts — cadence-api');
  assert.equal(e.url, null);
  assert.equal(e.project, 'cadence-api');
  assert.equal(e.category, null);
  assert.equal(e.is_idle, false);
  assert.deepEqual(e.meta, { lang: 'typescript' });
});

test('duration_ms equals the ts_end - ts_start millisecond delta (daemon Validate)', () => {
  const e = mapSegment(seg(), MAP_OPTS);
  assert.equal(e.duration_ms, Date.parse(e.ts_end) - Date.parse(e.ts_start));
});

test('every contract key is present even when nullable fields are empty', () => {
  const e = mapSegment(
    seg({ context: { fileName: null, languageId: null, project: null } }),
    MAP_OPTS
  );
  const keys = Object.keys(e).sort();
  assert.deepEqual(keys, [
    'app', 'category', 'duration_ms', 'event_id', 'is_idle', 'member_id',
    'meta', 'project', 'schema_ver', 'source', 'title', 'ts_end', 'ts_start', 'url',
  ].sort());
  assert.equal(e.title, null);
  assert.equal(e.project, null);
  assert.deepEqual(e.meta, { lang: null });
});

test('title omits project when there is no workspace folder, never an absolute path', () => {
  const e = mapSegment(
    seg({ context: { fileName: 'scratch.py', languageId: 'python', project: null } }),
    MAP_OPTS
  );
  assert.equal(e.title, 'scratch.py');
});

test('emitter buffers and sends events as one batch on flush', async () => {
  const calls: { url: string; body: string }[] = [];
  const fetchImpl: FetchLike = async (url, init) => {
    calls.push({ url, body: init.body });
    return { ok: true, status: 200 };
  };
  const emitter = new DaemonEmitter({
    baseUrl: 'http://127.0.0.1:47821',
    memberId: 'm',
    redactPaths: true,
    fetchImpl,
  });

  emitter.enqueue(seg());
  emitter.enqueue(seg());
  assert.equal(emitter.pending(), 2);

  const sent = await emitter.flush();
  assert.equal(sent, 2);
  assert.equal(emitter.pending(), 0);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'http://127.0.0.1:47821/events');
  const body = JSON.parse(calls[0].body);
  assert.equal(Array.isArray(body), true);
  assert.equal(body.length, 2);
});

test('a failed flush keeps events buffered for retry; flush never throws', async () => {
  let attempt = 0;
  const fetchImpl: FetchLike = async () => {
    attempt++;
    if (attempt === 1) {
      throw new Error('ECONNREFUSED'); // daemon down
    }
    return { ok: true, status: 200 };
  };
  const emitter = new DaemonEmitter({
    baseUrl: 'http://127.0.0.1:47821',
    memberId: 'm',
    redactPaths: true,
    fetchImpl,
  });

  emitter.enqueue(seg());
  const first = await emitter.flush(); // daemon down
  assert.equal(first, 0);
  assert.equal(emitter.pending(), 1, 'event retained after failure');

  const second = await emitter.flush(); // daemon back
  assert.equal(second, 1);
  assert.equal(emitter.pending(), 0);
});

test('a non-2xx response keeps events buffered', async () => {
  const fetchImpl: FetchLike = async () => ({ ok: false, status: 500 });
  const emitter = new DaemonEmitter({
    baseUrl: 'http://127.0.0.1:47821',
    memberId: 'm',
    redactPaths: true,
    fetchImpl,
  });
  emitter.enqueue(seg());
  assert.equal(await emitter.flush(), 0);
  assert.equal(emitter.pending(), 1);
});

test('the queue is bounded: oldest events drop past the cap, and it is logged', () => {
  const logs: string[] = [];
  const emitter = new DaemonEmitter({
    baseUrl: 'http://127.0.0.1:47821',
    memberId: 'm',
    redactPaths: true,
    maxQueue: 3,
    log: (m) => logs.push(m),
  });
  for (let i = 0; i < 5; i++) {
    emitter.enqueue(seg());
  }
  assert.equal(emitter.pending(), 3, 'capped at maxQueue');
  assert.equal(emitter.dropped(), 2, 'two oldest dropped');
  assert.equal(logs.some((l) => l.includes('dropped')), true, 'drop is logged, not silent');
});

test('snapshot/restore round-trips the backlog (for surviving restarts)', () => {
  const a = new DaemonEmitter({ baseUrl: 'http://127.0.0.1:47821', memberId: 'm', redactPaths: true });
  a.enqueue(seg());
  a.enqueue(seg());
  const snap = a.snapshot();
  assert.equal(snap.length, 2);

  const b = new DaemonEmitter({ baseUrl: 'http://127.0.0.1:47821', memberId: 'm', redactPaths: true });
  b.enqueue(seg()); // a fresh event in the new session
  b.restore(snap); // restored events go ahead of it
  assert.equal(b.pending(), 3);
  assert.equal(b.snapshot()[0].event_id, snap[0].event_id, 'restored backlog sent first');
});

test('restore also respects the queue cap', () => {
  const e = new DaemonEmitter({ baseUrl: 'http://127.0.0.1:47821', memberId: 'm', redactPaths: true, maxQueue: 2 });
  e.restore([
    mapSegment(seg(), { memberId: 'm', redactPaths: true, eventId: 'a' }),
    mapSegment(seg(), { memberId: 'm', redactPaths: true, eventId: 'b' }),
    mapSegment(seg(), { memberId: 'm', redactPaths: true, eventId: 'c' }),
  ]);
  assert.equal(e.pending(), 2);
  assert.equal(e.snapshot()[0].event_id, 'b', 'oldest ("a") dropped to fit cap');
});
