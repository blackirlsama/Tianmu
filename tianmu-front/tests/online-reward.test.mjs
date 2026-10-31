import test from 'node:test'
import assert from 'node:assert/strict'
import { createOnlineRewardMonitor } from '../src/lib/online-reward.mjs'

const settle = () => new Promise(resolve => setImmediate(resolve))
const pending = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
function fixture(request) {
  const state = { identity: 'alice', visible: true, results: [], ticks: [], cleared: [] }
  state.monitor = createOnlineRewardMonitor({
    session: () => state.identity, visible: () => state.visible,
    request, apply: result => state.results.push(result),
    every: (fn, ms) => { assert.equal(ms, 60000); state.ticks.push(fn); return 1 },
    clear: id => state.cleared.push(id),
  })
  return state
}
test('restored session checks presence without a login action and keeps checking across days', async () => {
  let calls = 0
  const state = fixture(async () => ({ coinCount: ++calls }))
  state.monitor.start(); await settle()
  assert.equal(calls, 1)
  await state.ticks[0](); assert.equal(calls, 2)
  state.monitor.stop(); assert.deepEqual(state.cleared, [1])
})
test('guests and hidden pages do not send presence, returning to a visible session does', async () => {
  let calls = 0
  const state = fixture(async () => ++calls)
  state.identity = ''; state.monitor.start(); await settle(); assert.equal(calls, 0)
  state.identity = 'alice'; state.visible = false; await state.monitor.refresh(); assert.equal(calls, 0)
  state.visible = true; await state.monitor.refresh(); assert.equal(calls, 1); state.monitor.stop()
})
test('requests do not overlap and logout discards a delayed response', async () => {
  const work = pending(); let calls = 0; let signal
  const state = fixture(s => { signal = s; calls++; return work.promise })
  state.monitor.start(); await state.monitor.refresh(); assert.equal(calls, 1)
  state.identity = ''; await state.monitor.refresh(); assert.equal(signal.aborted, true)
  work.resolve({ coinCount: 7 }); await settle(); assert.deepEqual(state.results, []); state.monitor.stop()
})
test('account changes cancel old checks and cannot apply an old account balance', async () => {
  const old = pending(), current = pending(); let calls = 0
  const state = fixture(() => (++calls === 1 ? old : current).promise)
  state.monitor.start(); state.identity = 'bob'; const next = state.monitor.refresh()
  old.resolve({ user: 'alice' }); current.resolve({ user: 'bob' }); await next; await settle()
  assert.deepEqual(state.results, [{ user: 'bob' }]); state.monitor.stop()
})
test('failed checks retry and stopped monitors ignore late responses', async () => {
  let calls = 0; const work = pending()
  const state = fixture(() => ++calls === 1 ? Promise.reject(new Error('offline')) : work.promise)
  state.monitor.start(); await settle()
  const next = state.monitor.refresh(); state.monitor.stop(); work.resolve({ coinCount: 3 }); await next
  assert.equal(calls, 2); assert.deepEqual(state.results, [])
})
