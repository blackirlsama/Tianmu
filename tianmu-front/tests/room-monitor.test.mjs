import test from 'node:test'
import assert from 'node:assert/strict'
import { createRoomMonitor } from '../src/agent/room-monitor.mjs'
const pending = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const settle = () => new Promise(resolve => setImmediate(resolve))
test('closing a room aborts its request and fences a delayed private result', async () => {
  const work = pending(), published = []; let signal
  const monitor = createRoomMonitor({ fetchSnapshot(s) { signal = s; return work.promise }, onSnapshot: s => published.push(s), onError: assert.fail })
  monitor.start(); monitor.stop(); assert.equal(signal.aborted, true)
  work.resolve({ private: 'old account' }); await settle(); assert.deepEqual(published, [])
})
test('starting a new room ignores results and errors from the previous room', async () => {
  const old = pending(), current = pending(), published = [], errors = []; let calls = 0
  const monitor = createRoomMonitor({ fetchSnapshot: () => (++calls === 1 ? old : current).promise, onSnapshot: s => published.push(s), onError: e => errors.push(e), setTimer: () => 1, clearTimer: () => {} })
  monitor.start(); monitor.start(); old.reject(new Error('old request')); current.resolve({ room: 'new' }); await settle()
  assert.deepEqual(published, [{ room: 'new' }]); assert.deepEqual(errors, []); monitor.stop()
})
test('polling never overlaps and authorization failure stops the next refresh', async () => {
  const first = pending(), next = pending(), timers = []; let calls = 0, errors = 0
  const monitor = createRoomMonitor({ fetchSnapshot: () => (++calls === 1 ? first : next).promise, onSnapshot() {}, onError() { errors++ }, setTimer(f, ms) { assert.equal(ms, 15000); timers.push(f); return timers.length }, clearTimer() {} })
  monitor.start(); assert.equal(calls, 1); assert.equal(timers.length, 0)
  first.resolve({}); await settle(); assert.equal(timers.length, 1); timers.shift()(); assert.equal(calls, 2)
  next.reject(Object.assign(new Error('denied'), { status: 403 })); await settle(); assert.equal(errors, 1); assert.equal(timers.length, 0)
  monitor.stop()
})
