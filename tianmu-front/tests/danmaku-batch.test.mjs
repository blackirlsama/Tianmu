import test from 'node:test'
import assert from 'node:assert/strict'
import { danmakuItems, splitDanmaku } from '../src/lib/danmaku-batch.mjs'
const item = (id, userId = '1') => ({ bulletId: id, userId, text: `text ${id}`, playbackTime: 1.25 })
test('room batches preserve independent IDs, playback times and order', () => {
  const result = danmakuItems({ type: 5, data: [item('a'), item('b', '2')] })
  assert.deepEqual(result, [
    { bulletId: 'a', userId: '1', text: 'text a', time: 1.25 },
    { bulletId: 'b', userId: '2', text: 'text b', time: 1.25 },
  ])
})
test('a mixed batch keeps self-send detection and still delivers other viewers messages', () => {
  const result = splitDanmaku(danmakuItems({ type: 5, data: [item('a'), item('b', '2'), item('c')] }), 1)
  assert.deepEqual(result.own.map(item => item.bulletId), ['a', 'c'])
  assert.deepEqual(result.others.map(item => item.bulletId), ['b'])
})
test('the previous single-item envelope is still readable during frontend-first rollout', () => {
  assert.deepEqual(danmakuItems({ type: 2, data: item('a') }), danmakuItems({ type: 5, data: [item('a')] }))
})
test('control messages, invalid batches and invalid entries do not enter the player queue', () => {
  for (const message of [{ type: 1, data: 30 }, { type: 4, data: 'error' }, { type: 5, data: {} },
    { type: 5, data: Array(1001).fill(item('a')) }]) assert.deepEqual(danmakuItems(message), [])
  assert.deepEqual(danmakuItems({ type: 5, data: [null, {}, { ...item('a'), playbackTime: -1 }] }), [])
})
