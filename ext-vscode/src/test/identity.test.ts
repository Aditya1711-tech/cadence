// Tests for the provisional local member-id resolver (P1-B.4).

import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { MEMBER_ID_KEY, MemberIdStore, resolveMemberId } from '../identity';

function fakeStore(initial?: string): MemberIdStore & { value?: string } {
  const s = {
    value: initial,
    get(_key: string) {
      return s.value;
    },
    update(_key: string, value: string) {
      s.value = value;
    },
  };
  return s;
}

test('generates and persists a member id on first use', () => {
  const store = fakeStore();
  let made = 0;
  const id = resolveMemberId(store, () => `generated-${++made}`);
  assert.equal(id, 'generated-1');
  assert.equal(store.value, 'generated-1');
  assert.equal(store.get(MEMBER_ID_KEY), 'generated-1');
});

test('reuses the persisted member id on subsequent calls', () => {
  const store = fakeStore('existing-id');
  let made = 0;
  const id = resolveMemberId(store, () => `generated-${++made}`);
  assert.equal(id, 'existing-id');
  assert.equal(made, 0, 'no new id generated when one already exists');
});

test('treats blank stored ids as absent', () => {
  const store = fakeStore('   ');
  const id = resolveMemberId(store, () => 'fresh');
  assert.equal(id, 'fresh');
});
