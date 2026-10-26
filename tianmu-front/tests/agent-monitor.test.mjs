import test from 'node:test';
import assert from 'node:assert/strict';
import { createRunMonitor } from '../src/agent/run-monitor.mjs';

const tick = () => new Promise(resolve => setImmediate(resolve));
test('closing monitoring aborts the active subscription without cancelling the server task', async () => {
  let signal;
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: () => assert.fail('late snapshot'),
    getAgentRun: () => assert.fail('no polling after close'),
    readAgentEvents: (id, cursor, current) => new Promise(resolve => {
      signal = current; current.addEventListener('abort', resolve, { once: true });
    }) });
  const done = monitor.start({ runId: 'run', events: [] });
  monitor.stop(); await done;
  assert.equal(signal.aborted, true);
});
test('a delayed snapshot from the previous conversation cannot overwrite the new conversation', async () => {
  let resolveOld; const applied = [];
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: (turn, snapshot) => { turn.snapshot = snapshot; applied.push(turn.runId); },
    readAgentEvents: async () => {},
    getAgentRun: id => id === 'old' ? new Promise(resolve => { resolveOld = resolve; }) : Promise.resolve({ status: 'SUCCEEDED' }) });
  try {
    const old = monitor.start({ runId: 'old', events: [] }); await tick();
    const current = monitor.start({ runId: 'current', events: [] }); await current;
    resolveOld({ status: 'SUCCEEDED' }); await old;
    assert.deepEqual(applied, ['current']);
  } finally { monitor.stop(); }
});
test('replayed events are deduplicated and terminal refresh ends the subscription', async () => {
  const turn = { runId: 'run', events: [] }; let refreshed = 0;
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: (t, snapshot) => { t.snapshot = snapshot; refreshed++; },
    getAgentRun: async () => ({ status: 'SUCCEEDED' }),
    readAgentEvents: async (id, cursor, signal, onEvent) => {
      const event = { type: 'PROGRESS', id: '1', payload: { sequence: 1, data: { stage: 'preparing' } } };
      onEvent(event); onEvent(event);
    } });
  try { await monitor.start(turn); assert.equal(turn.events.length, 1); assert.equal(refreshed, 1); }
  finally { monitor.stop(); }
});
test('authorization failure stops monitoring and preserves the actionable error', async () => {
  const notice = { value: '' }; let calls = 0;
  const monitor = createRunMonitor({ notice, applySnapshot: () => assert.fail('unauthorized'),
    getAgentRun: () => assert.fail('unauthorized'),
    readAgentEvents: async () => { calls++; throw Object.assign(new Error('Access revoked'), { status: 403 }); } });
  try { await monitor.start({ runId: 'run', events: [] }); assert.equal(calls, 1); assert.equal(notice.value, 'Access revoked'); assert.equal(monitor.signal.aborted, true); }
  finally { monitor.stop(); }
});
