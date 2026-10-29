import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTraceFile, layoutTrace } from '../src/trace/trace-data.mjs'
import { demoFiles } from '../src/trace/demo-trace.mjs'

test('run token summaries are not double-counted with child model usage', () => {
  const [trace] = parseTraceFile(JSON.stringify(demoFiles[0]))
  assert.equal(trace.totalTokens, 604)
  assert.equal(trace.durationMs, 1420)
  const model = trace.nodes.find((node) => node.type === 'llm_inference')
  assert.equal(model.durationMs, 420)
  assert.equal(model.input.messages[0].role, 'user')
  assert.equal(model.output.decision, 'CALL_TOOL')
})

test('JSONL replay is deduplicated and separate traces remain separate', () => {
  const first = demoFiles[0].spans
  const records = [...first, first[0], ...demoFiles[1].spans]
  const traces = parseTraceFile(records.map((span) => JSON.stringify(span)).join('\n'))
  assert.equal(traces.length, 2)
  assert.equal(traces[0].nodes.length, first.length)
  assert.equal(traces[1].nodes.find((node) => node.kind === 'TOOL').status, 'TIMED_OUT')
})

test('raw exports preserve missing payloads, unknown status and unknown token counts', () => {
  const [trace] = parseTraceFile(JSON.stringify([{
    traceId: 'trace', spanId: 'node', name: 'agent.model', parentSpanId: 'absent', status: 'UNSET',
    startTimeUnixNano: '1793240671000000000', endTimeUnixNano: '1793240671001000001',
    attributes: { 'node.type': 'MODEL', 'detail.input_ref': 'private-reference' },
  }]))
  assert.equal(trace.totalTokens, null)
  assert.equal(trace.nodes[0].status, 'UNKNOWN')
  assert.equal(trace.nodes[0].input, null)
  assert.equal(trace.nodes[0].inputRef, 'private-reference')
  assert.equal(trace.nodes[0].durationMs, 1.000001)
  assert.deepEqual(layoutTrace(trace.nodes)[0].position, { x: 0, y: 0 })
})

test('reject malformed exports and cyclic relationships before drawing', () => {
  for (const value of ['{invalid', 'null', '[]', '{"spans":[{}]}']) {
    assert.throws(() => parseTraceFile(value))
  }
  const spans = [{ traceId: 't', spanId: 'a', parentSpanId: 'b', name: 'a' }, { traceId: 't', spanId: 'b', parentSpanId: 'a', name: 'b' }]
  assert.throws(() => parseTraceFile(JSON.stringify(spans)), /循环/)
})
