import test from 'node:test'
import assert from 'node:assert/strict'
import { parseEvaluationFile, summarizeRuns, filterRuns, modelSummaries, accuracyCells, boxSummary, datasetWarnings, exportSummaryCsv } from '../src/trace/evaluation-data.mjs'
import { demoFiles } from '../src/trace/demo-trace.mjs'
import { createEvaluationDemo } from '../src/trace/evaluation-demo.mjs'

const dataset = (runs) => parseEvaluationFile(JSON.stringify({ runs })).runs
test('correctness is never inferred from runtime status and missing metrics stay missing', () => {
  const runs = dataset([
    { runId: '1', model: 'A', taskId: 'q', status: 'SUCCEEDED', durationMs: 1000, totalTokens: 100, usageComplete: true, correct: false },
    { runId: '2', model: 'A', taskId: 'q', status: 'SUCCEEDED', totalTokens: 200, usageComplete: false },
    { runId: '3', model: 'B', taskId: 'q', status: 'FAILED', durationMs: 3000, correct: true },
  ])
  const summary = summarizeRuns(runs)
  assert.equal(summary.accuracy, 0.5)
  assert.equal(summary.executionRate, 2 / 3)
  assert.equal(summary.evaluated, 2)
  assert.equal(summary.meanTokens, 100)
  assert.equal(summary.tokenCount, 1)
  assert.equal(summary.medianMs, 2000)
  assert.equal(runs[1].durationMs, null)
})

test('model, task, verdict, condition and search filters compose with correct denominators', () => {
  const demo = parseEvaluationFile(JSON.stringify(createEvaluationDemo()))
  assert.equal(demo.runs.length, 36)
  assert.equal(demo.demo, true)
  const runs = filterRuns(demo.runs, { models: ['Model A'], tasks: ['metric'], verdict: 'incorrect', search: 'demo-a' })
  assert.equal(runs.length, 2)
  assert.equal(summarizeRuns(runs).accuracy, 0)
  assert.equal(modelSummaries(runs).length, 1)
  const matrix = accuracyCells(demo.runs)
  assert.equal(matrix.cells.length, 9)
  assert.equal(matrix.cells[0].evaluated, 4)
  assert.equal(matrix.cells[0].accuracy, 0.5)
  assert.equal(filterRuns(demo.runs, { condition: 'missing' }).length, 0)
})

test('JSONL replay and SSE subscriptions do not multiply experiment runs', () => {
  const spans = demoFiles[0].spans.map((span) => JSON.parse(JSON.stringify(span)))
  const sse = { ...spans[0], traceId: 'd'.repeat(32), spanId: 'e'.repeat(16), name: 'terminal_delivery' }
  const result = parseEvaluationFile([...spans, spans[0], sse].map((span) => JSON.stringify(span)).join('\n'))
  assert.equal(result.runs.length, 1)
  assert.equal(result.runs[0].trace.nodes.length, spans.length)
  assert.equal(result.runs[0].toolCalls, 1)
})

test('reject mismatched run traces, duplicate runs and mixed synthetic evidence', () => {
  assert.throws(() => dataset([{ runId: 'wrong-owner', trace: demoFiles[0] }]), /不匹配/)
  assert.throws(() => dataset([{ runId: 'same' }, { runId: 'same' }]), /重复/)
  assert.throws(() => dataset([{ runId: '1', demo: true }, { runId: '2' }]), /不能放在同一/)
  for (const text of ['null', '{invalid', '{"runs":[]}', '{"runs":[null]}']) assert.throws(() => parseEvaluationFile(text))
})

test('quartiles use interpolation and whiskers exclude extreme outliers', () => {
  const box = boxSummary([1, 2, 3, 4, 5, 6, 7, 100])
  assert.deepEqual(box.values, [1, 2.75, 4.5, 6.25, 7])
  assert.equal(boxSummary([]), null)
})

test('summary export guards spreadsheet formulas and warnings expose unequal task coverage', () => {
  const runs = dataset([{ runId: '=1+1', model: 'A', taskId: 'q', correct: true }, { runId: '2', model: 'B', taskId: 'other' }])
  assert.match(exportSummaryCsv(runs), /'\=1\+1/)
  assert.ok(datasetWarnings(runs).some((warning) => warning.includes('覆盖的任务不同')))
  assert.ok(datasetWarnings(runs).some((warning) => warning.includes('缺少评测依据')))
})
