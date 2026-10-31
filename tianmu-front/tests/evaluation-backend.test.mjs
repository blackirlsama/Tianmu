import test from 'node:test'
import assert from 'node:assert/strict'
import { parseRunIds, loadEvaluationRuns } from '../src/trace/evaluation-backend.mjs'
import { exportEvaluationDataset, parseEvaluationFile } from '../src/trace/evaluation-data.mjs'
import { demoFiles } from '../src/trace/demo-trace.mjs'

const id = '11111111-2222-4333-8444-555555555555'
function response() {
  const spans = JSON.parse(JSON.stringify(demoFiles[0].spans))
  spans.forEach((span) => { span.attributes['run.id'] = id })
  return { run: { runId: id, status: 'SUCCEEDED', traceId: spans[0].traceId, task: { specification: { type: 'metric' } } },
    spans, modelMode: 'PROVIDER', businessMode: 'BUSINESS', truncated: false }
}

test('batch read deduplicates IDs and derives model metrics from actual Trace while leaving correctness unknown', async () => {
  assert.deepEqual(parseRunIds(`${id}\n${id}`), [id])
  assert.throws(() => parseRunIds('invalid'), /格式/)
  const requested = []
  const result = await loadEvaluationRuns({ text: `${id}\n${id}`, signal: new AbortController().signal,
    client: { getTrace: async (runId) => { requested.push(runId); return response() } }, conditionId: 'same-v1' })
  assert.equal(requested.length, 1)
  const row = result.dataset.runs[0]
  assert.equal(row.model, 'deepseek-chat')
  assert.equal(row.correct, null)
  assert.equal(row.durationMs, 1420)
  assert.equal(row.conditionId, 'same-v1')
  assert.ok(row.trace.live)
  const roundtrip = parseEvaluationFile(exportEvaluationDataset(result.dataset.runs))
  assert.equal(roundtrip.runs[0].trace.nodes.length, row.trace.nodes.length)
  assert.equal(roundtrip.runs[0].correct, null)
})

test('truncated, wrong-run and FAQ-only traces are never silently counted as real experiments', async () => {
  for (const modify of [data => { data.truncated = true }, data => { data.run.runId = 'another' }, data => { data.run.status = 'RUNNING' }, data => { data.spans = data.spans.filter(s => s.attributes['span.type'] !== 'llm_inference') }]) {
    const data = response(); modify(data)
    const result = await loadEvaluationRuns({ text: id, signal: new AbortController().signal, client: { getTrace: async () => data } })
    assert.equal(result.dataset, null)
    assert.equal(result.errors.length, 1)
  }
})

test('authorization failure or cancellation stops the batch without returning partial private datasets', async () => {
  await assert.rejects(loadEvaluationRuns({ text: id, signal: new AbortController().signal,
    client: { getTrace: async () => { throw Object.assign(new Error('denied'), { status: 403 }) } } }), /denied/)
  const controller = new AbortController(); controller.abort()
  let requests = 0
  await assert.rejects(loadEvaluationRuns({ text: id, signal: controller.signal, client: { getTrace: async () => { requests++ } } }), { name: 'AbortError' })
  assert.equal(requests, 0)
})

test('portable export preserves actual payloads, demo marking and explicit review metadata', () => {
  const imported = parseEvaluationFile(JSON.stringify({ demo: true, runs: [{ runId: demoFiles[0].spans[0].attributes['run.id'],
    trace: demoFiles[0], evaluation: { correct: false, basis: 'test-only', reviewerRef: 'r1', reviewedAt: '2026-10-31T00:00:00Z' } }] }))
  const result = parseEvaluationFile(exportEvaluationDataset(imported.runs))
  assert.equal(result.demo, true)
  assert.equal(result.runs[0].correct, false)
  assert.equal(result.runs[0].reviewerRef, 'r1')
  assert.ok(result.runs[0].trace.nodes.some((node) => node.input != null))
})
