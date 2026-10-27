import test from 'node:test'
import assert from 'node:assert/strict'
import {
  answerReport,
  buildReportMessage,
  buildChatMessage,
  buildChatRequest,
  answerPresentation,
  formatDataUpdatedAt,
  chinaTime,
  reportPath,
  sseDecoder,
  yesterdayRange,
  sevenDayRange,
} from '../src/agent/protocol.mjs'

const now = new Date('2026-10-25T04:00:00Z')
test('answer notes stay separate, preserve evidence wording and display Beijing time', () => {
  const answer = { text: '查到了，这个视频在10月24日共有2,400条弹幕。', dataNotes: [{ text: '当前保留的记录', updatedAt: '2026-10-25T07:19:48Z' }] }
  assert.deepEqual(answerPresentation(answer), answerPresentation(JSON.parse(JSON.stringify(answer))))
  assert.equal(answerPresentation(answer).text, answer.text)
  assert.equal(answerPresentation(answer).notes.length, 1)
  assert.match(formatDataUpdatedAt(answer.dataNotes[0].updatedAt), /15:19:48/)
  assert.equal(formatDataUpdatedAt('invalid'), '')
  const old = answerPresentation({ text: '视频1001在2026-10-24的弹幕数为2400条（已持久化保留口径，数据截至2026-10-25T07:19:48Z）。\n统计口径：按创建日期统计当前已入库且仍保留的弹幕记录；不代表全部已发送弹幕或历史删除记录。' })
  assert.equal(old.text, '视频1001在2026-10-24的弹幕数为2400条。')
  assert.equal(old.notes.length, 1)
  assert.match(old.notes[0].text, /不代表全部已发送/)
  assert.equal(old.notes[0].updatedAt, '2026-10-25T07:19:48Z')
  assert.equal(answerPresentation({ text: '统计口径问题需要具体解释：这个日期怎么算？' }).text, '统计口径问题需要具体解释：这个日期怎么算？')
})
test('chat preserves the user words and carries page selection in a separate context', () => {
  assert.deepEqual(buildChatRequest('  昨天有多少条弹幕？ ', '1001'), { message: '昨天有多少条弹幕？', context: { videoId: '1001' } })
  assert.deepEqual(buildChatRequest('你能做什么', '1001'), { message: '你能做什么', context: { videoId: '1001' } })
  assert.equal(buildChatRequest('查询视频1002昨天的弹幕数', '1001').message, '查询视频1002昨天的弹幕数')
  assert.equal(buildChatMessage('我想聊聊内容运营'), '我想聊聊内容运营')
  assert.deepEqual(buildChatRequest('昨天', '1001'), { message: '昨天', context: { videoId: '1001' } })
  assert.throws(() => buildChatRequest('  ', '1001'))
  assert.throws(() => buildChatRequest('问题', '9223372036854775808'))
  assert.equal(buildChatRequest('字'.repeat(2000), '1001').message.length, 2000)
  assert.throws(() => buildChatRequest('字'.repeat(2001), '1001'))
})
const input = {
  videoId: '987654321098765432',
  start: '2026-10-24T20:00',
  end: '2026-10-24T22:00',
  focus: '分析互动高峰',
}
test('report requests preserve long video IDs and explicit Beijing wall times', () => {
  assert.equal(
    buildReportMessage(input, now),
    '请分析视频987654321098765432从2026-10-24 20:00到2026-10-24 22:00的弹幕和评论，生成一份观众反馈报告。重点关注：分析互动高峰'
  )
  for (const changes of [
    { videoId: '9223372036854775808' },
    { end: input.start },
    { end: '2026-10-26T00:00' },
    { start: '2026-09-01T00:00' },
    { start: '2026-02-30T10:00' },
    { start: '2026-09-20T00:00', end: '2026-10-25T01:00' },
  ]) {
    assert.throws(() => buildReportMessage({ ...input, ...changes }, now))
  }
})

test('seven day report defaults include the partial current day and support multi-day ranges', () => {
  assert.deepEqual(sevenDayRange(now), { start: '2026-10-18T12:00', end: '2026-10-25T12:00' })
  assert.match(buildReportMessage({ ...input, ...sevenDayRange(now) }, now), /2026-10-18 12:00到2026-10-25 12:00/)
})
test('default ranges use Beijing dates across timezone day boundaries', () => {
  assert.equal(chinaTime(new Date('2026-10-24T18:00:00Z')), '2026-10-25T02:00')
  assert.deepEqual(yesterdayRange(now), {
    start: '2026-10-24T00:00',
    end: '2026-10-25T00:00',
  })
})
test('SSE handles arbitrary byte chunks, CRLF, multi-line JSON and replay IDs', () => {
  const events = [],
    parser = sseDecoder((event) => events.push(event)),
    decoder = new TextDecoder()
  const bytes = new TextEncoder().encode(
    ': heartbeat\r\n\r\nid: 8\r\nevent: PROGRESS\r\ndata: {"data":\r\ndata: {"label":"弹幕分析"},"sequence":8}\r\n\r\nid: 9\nevent: DONE\ndata: {}\n\n'
  )
  for (const byte of bytes)
    parser.push(decoder.decode(new Uint8Array([byte]), { stream: true }))
  parser.push(decoder.decode())
  assert.equal(events.length, 2)
  assert.equal(events[0].id, '8')
  assert.equal(events[0].payload.data.label, '弹幕分析')
  assert.equal(events[1].type, 'DONE')
})
test('reports must belong to this run and use the authenticated report endpoint', () => {
  const run = '12345678-1234-1234-1234-123456789abc',
    artifact = 'rp_12345678-1234-1234-1234-123456789abc'
  const path = `/api/agent/runs/${run}/reports/${artifact}`
  assert.equal(answerReport(run, `[查看 HTML 报告](${path})`), path)
  for (const unsafe of [
    `https://example.com${path}`,
    `${path}/../secret`,
    path.replace(run, 'aaaaaaaa-1234-1234-1234-123456789abc'),
  ])
    assert.equal(reportPath(run, unsafe), '')
})
