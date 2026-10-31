import test from 'node:test'
import assert from 'node:assert/strict'
import { danmakuMessage } from '../src/lib/danmaku-message.mjs'
const message = { userId: 'victim', videoId: '2114367144786530304', content: 'hello', playbackTime: 1.25 }
test('private send envelope carries the current credential but excludes client identity and event metadata', () => {
  const result = JSON.parse(danmakuMessage({ ...message, bulletId: 'forged', createTime: 'yesterday' }, 'current-session'))
  assert.deepEqual(result, { authorization: 'current-session', videoId: message.videoId, content: 'hello', playbackTime: 1.25 })
  assert.equal(JSON.parse(danmakuMessage(message, 'new-session')).authorization, 'new-session')
})
test('missing credentials and invalid data cannot become send frames', () => {
  assert.throws(() => danmakuMessage(message, ''))
  for (const patch of [{ content: ' ' }, { content: 'x'.repeat(256) }, { playbackTime: -1 },
    { playbackTime: NaN }, { playbackTime: Infinity }, { videoId: '99?token=secret' }, { videoId: '9223372036854775808' }]) {
    assert.throws(() => danmakuMessage({ ...message, ...patch }, 'current-session'))
  }
})
