import { demoFiles } from './demo-trace.mjs'

// Deterministic synthetic interaction fixtures. The UI never loads them implicitly.
export function createEvaluationDemo() {
  const runs = []
  const models = ['Model A', 'Model B', 'Model C']
  const tasks = [{ id: 'metric', name: '指标查询' }, { id: 'classify', name: '反馈分类' }, { id: 'report', name: '报告生成' }]
  for (let m = 0; m < 3; m++) for (let t = 0; t < 3; t++) for (let repeat = 0; repeat < 4; repeat++) {
    const index = runs.length + 1
    const failed = repeat === 3 && (m + t) % 2 === 0
    const trace = JSON.parse(JSON.stringify(demoFiles[failed ? 1 : 0]))
    const runId = `demo-${models[m].slice(-1).toLowerCase()}-${tasks[t].id}-${repeat + 1}`
    const durationMs = 900 + m * 420 + t * 650 + repeat * 190 + (index === 36 ? 9000 : 0)
    const originalMs = failed ? 1950 : 1420
    const ratio = durationMs / originalMs
    const traceId = index.toString(16).padStart(32, '0')
    const inputTokens = 300 + t * 220 + repeat * 31 + m * 75, outputTokens = 100 + t * 150 + m * 60
    for (const span of trace.spans) {
      span.traceId = traceId
      span.eventId = `${index.toString(16).padStart(8, '0')}-aaaa-4aaa-8aaa-${span.spanId.slice(-12)}`
      span.attributes['run.id'] = runId
      span.attributes['task.id'] = tasks[t].id
      span.attributes['task.name'] = tasks[t].name
      if (span.attributes['model.name']) span.attributes['model.name'] = models[m]
      if (span.attributes['usage.scope'] === 'RUN') {
        span.attributes['run.e2e_ms'] = durationMs
        span.attributes['usage.input_tokens'] = inputTokens
        span.attributes['usage.output_tokens'] = outputTokens
      }
      const start = BigInt(trace.spans[0].startTimeUnixNano)
      const offset = Number(BigInt(span.startTimeUnixNano) - start) / 1e6
      span.startTimeUnixNano = String(start + BigInt(Math.round(offset * ratio * 1e6)))
      span.durationNanos = Math.round(span.durationNanos * ratio)
      span.endTimeUnixNano = String(BigInt(span.startTimeUnixNano) + BigInt(span.durationNanos))
    }
    for (const detail of Object.values(trace.details)) {
      // Examples describe their simulated task and outcome, rather than a real business request.
      if (detail.input?.messages) detail.input = { model: models[m], messages: [{ role: 'user', content: `演示任务：${tasks[t].name}，样本 ${repeat + 1}` }] }
    }
    trace.title = `${tasks[t].name} · ${models[m]} · ${repeat + 1}`
    runs.push({ runId, model: models[m], taskId: tasks[t].id, taskName: tasks[t].name, conditionId: '统一提示与工具 · 演示',
      durationMs, totalTokens: inputTokens + outputTokens, usageComplete: index !== 5,
      status: failed ? 'FAILED' : 'SUCCEEDED', evaluation: { correct: !failed && (repeat !== 2 || m === 1), basis: '合成标签，仅用于界面联调' },
      trace, demo: true })
  }
  return { schemaVersion: 'tianmu-evaluation/1', demo: true, title: '三模型 × 三任务 · 交互演示', runs }
}
