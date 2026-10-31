import { parseTraceFile } from './trace-data.mjs'

const number = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
const label = (value, fallback) => typeof value === 'string' && value.trim() ? value.trim().slice(0, 160) : fallback
const verdict = (value) => typeof value === 'boolean' ? value : null
function checkedDataset(data) {
  const keys = new Set()
  for (const run of data.runs) {
    if (keys.has(run.key)) throw new Error(`运行 ID 重复：${run.runId}。请先去重。`)
    keys.add(run.key)
  }
  if (new Set(data.runs.map((run) => run.modelLabel)).size > 20 || new Set(data.runs.map((run) => run.taskId)).size > 50) {
    throw new Error('单批最多支持 20 个模型和 50 个任务，请按实验分组导入。')
  }
  return data
}
export const mean = (values) => {
  const available = values.filter((value) => number(value) != null)
  return available.length ? available.reduce((sum, value) => sum + value, 0) / available.length : null
}
export function quantile(values, q) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const position = (sorted.length - 1) * q, lower = Math.floor(position)
  return sorted[lower] + (sorted[Math.ceil(position)] - sorted[lower]) * (position - lower)
}
export function boxSummary(values) {
  const sorted = values.filter((v) => number(v) != null).sort((a, b) => a - b)
  if (!sorted.length) return null
  const q1 = quantile(sorted, 0.25), median = quantile(sorted, 0.5), q3 = quantile(sorted, 0.75)
  const iqr = q3 - q1
  const inside = sorted.filter((value) => value >= q1 - 1.5 * iqr && value <= q3 + 1.5 * iqr)
  return { values: [inside[0], q1, median, q3, inside[inside.length - 1]],
    low: q1 - 1.5 * iqr, high: q3 + 1.5 * iqr, count: sorted.length }
}

export function parseEvaluationFile(text, source = '导入文件') {
  let payload
  try { payload = JSON.parse(text) }
  catch {
    try { payload = text.split(/\r?\n/).filter((line) => line.trim()).map((line) => JSON.parse(line)) }
    catch { throw new Error('解析失败，请使用评测 JSON、JSONL 或 Tianmu TraceEnvelope 日志。') }
  }
  if (payload?.runs) {
    if (!Array.isArray(payload.runs) || !payload.runs.length) throw new Error('评测数据 runs 必须是非空数组。')
    if (payload.runs.length > 5000) throw new Error('单批最多 5,000 次运行，请拆分数据集。')
    const seen = new Set()
    const runs = payload.runs.map((row, index) => {
      const run = normalizeEvaluationRun({ ...row, normalizedTrace: undefined }, { source, demo: payload.demo === true, index })
      if (seen.has(run.key)) throw new Error(`运行 ID 重复：${run.runId}。请先去重。`)
      seen.add(run.key)
      return run
    })
    const demoStates = new Set(runs.map((run) => run.demo))
    if (demoStates.size > 1) throw new Error('演示运行与非演示运行不能放在同一数据集中。')
    return checkedDataset({ title: label(payload.title, source), source, demo: runs.every((r) => r.demo), runs })
  }
  const records = Array.isArray(payload) ? payload : payload?.spans ? [payload] : payload?.spanId ? [payload] : null
  if (!records?.length) throw new Error('没有找到 runs 评测数组或 Trace Span。')
  if (records[0]?.spanId) {
    if (records.length > 20000) throw new Error('批量日志最多 20,000 条 Span，请拆分后导入。')
    const groups = new Map()
    for (const span of records) {
      const id = `${span?.attributes?.['run.id'] || ''}/${span?.traceId || ''}`
      if (!groups.has(id)) groups.set(id, [])
      groups.get(id).push(span)
    }
    const candidates = [...groups.values()].map((spans, index) => normalizeEvaluationRun({ spans }, { source, index, demo: false }))
    const runs = new Map()
    for (const run of candidates) {
      const previous = runs.get(run.key)
      // A separate SSE subscription trace is not another experiment run.
      if (!previous || run.trace.nodes.some((node) => node.name === 'agent.run')) runs.set(run.key, run)
    }
    return checkedDataset({ title: source, source, demo: false, runs: [...runs.values()] })
  }
  if (records.every((row) => row?.spans)) {
    const runs = records.map((row, index) => normalizeEvaluationRun(row, { source, index, demo: payload.demo === true }))
    if (new Set(runs.map((run) => run.demo)).size > 1) throw new Error('演示运行与非演示运行不能混合。')
    return checkedDataset({ title: label(payload.title, source), source, demo: runs.every((r) => r.demo), runs })
  }
  throw new Error('格式不支持。汇总数据请使用 { runs: [...] }；调用日志请使用 TraceEnvelope 数组。')
}

export function normalizeEvaluationRun(row, { source = '后端运行', demo = false, index = 0 } = {}) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) throw new Error('每次运行必须是对象。')
  let trace = row.normalizedTrace || null
  const rawTrace = row.trace || (Array.isArray(row.spans) ? { spans: row.spans, details: row.details } : null)
  if (rawTrace?.spans?.length) {
    const parsed = parseTraceFile(JSON.stringify(rawTrace), source)
    if (parsed.length !== 1) throw new Error('每条评测运行只能关联一个 Trace，请按运行和 Trace 拆分。')
    trace = parsed[0]
  }
  const attrs = trace?.nodes.find((node) => node.name === 'agent.run')?.attributes || {}
  const runId = label(row.runId, trace?.runId)
  if (!runId) throw new Error(`第 ${index + 1} 条运行缺少 runId。`)
  const spanRunIds = new Set(trace?.nodes.map((node) => node.attributes['run.id']).filter(Boolean) || [])
  if (spanRunIds.size > 1 || (spanRunIds.size === 1 && !spanRunIds.has(runId))) throw new Error(`运行 ${runId} 的 Trace 与 runId 不匹配。`)
  const mainModels = [...new Set(trace?.nodes.filter((n) => n.type === 'llm_inference').map((n) => n.model).filter(Boolean) || [])]
  const model = label(row.model, mainModels.length ? mainModels.join(' + ') : trace?.model || '模型未标注')
  const modelVersion = label(row.modelVersion, '')
  const taskId = label(row.taskId, attrs['task.id'] || '未标注任务')
  const taskName = label(row.taskName, attrs['task.name'] || taskId)
  const isDemo = demo || row.demo === true || trace?.demo === true
  const durationMs = number(row.durationMs) ?? trace?.durationMs ?? null
  const totalTokens = number(row.totalTokens) ?? trace?.totalTokens ?? null
  const usageComplete = totalTokens != null && (row.usageComplete === true || (row.usageComplete == null && trace?.usageComplete === true))
  const toolNodes = trace?.nodes.filter((node) => node.type === 'tool_call' || node.kind === 'TOOL')
  const errorNodes = trace?.nodes.filter((node) => ['FAILED', 'TIMED_OUT'].includes(node.status)) || []
  const evaluation = row.evaluation || {}
  const correct = verdict(evaluation.correct) ?? verdict(row.correct)
  const conditionId = label(row.conditionId, '条件未记录')
  const status = label(row.status, trace?.status || 'UNKNOWN').toUpperCase()
  const input = { key: `${isDemo ? 'demo' : 'data'}:${runId}`, runId, taskId, taskName, model, modelVersion,
    modelLabel: modelVersion ? `${model} · ${modelVersion}` : model, conditionId,
    status, correct, basis: label(evaluation.basis || row.evaluationBasis, ''),
    durationMs, totalTokens, usageComplete, toolCalls: number(row.toolCalls) ?? toolNodes?.length ?? null,
    errorType: label(row.errorType, errorNodes.find((n) => n.attributes['error.code'])?.attributes['error.code'] || (errorNodes.length ? '节点错误' : '')),
    hasError: typeof row.hasError === 'boolean' ? row.hasError : errorNodes.length > 0 || ['FAILED', 'TIMED_OUT'].includes(status),
    fixRounds: number(row.fixRounds), startMs: trace?.startMs ?? (typeof row.startedAt === 'string' && Number.isFinite(Date.parse(row.startedAt)) ? Date.parse(row.startedAt) : null),
    demo: isDemo, source, trace, modelMode: row.modelMode || null, businessMode: row.businessMode || null }
  if (trace) { trace.demo = isDemo; trace.runId = runId }
  return input
}

export function filterRuns(runs, filters) {
  const search = (filters.search || '').trim().toLocaleLowerCase()
  return runs.filter((run) => (!filters.models?.length || filters.models.includes(run.modelLabel))
    && (!filters.tasks?.length || filters.tasks.includes(run.taskId))
    && (!filters.condition || run.conditionId === filters.condition)
    && (!filters.status || run.status === filters.status)
    && (!filters.verdict || (filters.verdict === 'correct' ? run.correct === true : filters.verdict === 'incorrect' ? run.correct === false : run.correct == null))
    && (!search || `${run.runId} ${run.taskName} ${run.modelLabel} ${run.errorType}`.toLocaleLowerCase().includes(search)))
}

export function summarizeRuns(runs) {
  const evaluated = runs.filter((run) => run.correct != null)
  const terminal = runs.filter((run) => ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'NEEDS_INPUT'].includes(run.status))
  const completeTokens = runs.filter((run) => run.totalTokens != null && run.usageComplete)
  return { count: runs.length, evaluated: evaluated.length, correct: evaluated.filter((run) => run.correct).length,
    accuracy: evaluated.length ? evaluated.filter((run) => run.correct).length / evaluated.length : null,
    executionRate: terminal.length ? terminal.filter((run) => run.status === 'SUCCEEDED').length / terminal.length : null,
    durationCount: runs.filter((run) => run.durationMs != null).length, medianMs: quantile(runs.map((run) => run.durationMs).filter((v) => v != null), 0.5),
    meanMs: mean(runs.map((run) => run.durationMs)), p95Ms: quantile(runs.map((run) => run.durationMs).filter((v) => v != null), 0.95),
    tokenCount: completeTokens.length, meanTokens: mean(completeTokens.map((run) => run.totalTokens)),
    meanTools: mean(runs.map((run) => run.toolCalls)), errorRuns: runs.filter((run) => run.hasError).length,
    models: [...new Set(runs.map((run) => run.modelLabel))], tasks: [...new Set(runs.map((run) => run.taskId))] }
}

export function modelSummaries(runs) {
  return [...new Set(runs.map((run) => run.modelLabel))].map((model) => {
    const selected = runs.filter((run) => run.modelLabel === model)
    return { model, ...summarizeRuns(selected), runs: selected, box: boxSummary(selected.map((run) => run.durationMs)) }
  })
}

export function accuracyCells(runs) {
  const models = [...new Set(runs.map((r) => r.modelLabel))], tasks = [...new Set(runs.map((r) => r.taskId))]
  return { models, tasks, cells: models.flatMap((model, y) => tasks.map((taskId, x) => {
    const subset = runs.filter((r) => r.modelLabel === model && r.taskId === taskId)
    return { model, taskId, x, y, ...summarizeRuns(subset) }
  })) }
}

export function datasetWarnings(runs) {
  const warnings = []
  if (!runs.length) return warnings
  if (runs.some((r) => r.demo)) warnings.push('当前为合成交互演示，所有数值仅用于预览，不能写入实验结论。')
  if (runs.some((r) => r.taskId === '未标注任务')) warnings.push('部分运行未标注任务；补充 taskId 后才能比较同一任务。')
  if (runs.some((r) => r.conditionId === '条件未记录')) warnings.push('部分实验条件未记录，无法确认提示词、工具、预算和输入是否一致。')
  if (new Set(runs.map((r) => r.conditionId)).size > 1) warnings.push('当前包含多组实验条件；建议筛选同一条件后比较模型。')
  if (runs.some((r) => r.correct != null && !r.basis)) warnings.push('部分正确性标签缺少评测依据，报告中需补充标注或对账标准。')
  if (runs.some((r) => r.modelMode === 'DEMO')) warnings.push('包含后端模拟模型运行，不能用于真实模型性能结论。')
  const models = [...new Set(runs.map((r) => r.modelLabel))]
  if (models.length > 1) {
    const taskSets = models.map((model) => new Set(runs.filter((r) => r.modelLabel === model).map((r) => r.taskId)))
    if (new Set(taskSets.map((set) => [...set].sort().join('|'))).size > 1) warnings.push('各模型覆盖的任务不同，总体指标可能受任务构成影响；请按任务查看。')
  }
  return warnings
}

export function findings(runs) {
  if (!runs.length) return []
  const summary = summarizeRuns(runs), models = modelSummaries(runs)
  const evaluated = models.filter((m) => m.accuracy != null).sort((a, b) => b.accuracy - a.accuracy)
  const fastest = models.filter((m) => m.medianMs != null).sort((a, b) => a.medianMs - b.medianMs)[0]
  const cells = accuracyCells(runs).cells.filter((cell) => cell.evaluated > 0)
  const slowest = runs.filter((run) => run.durationMs != null).sort((a, b) => b.durationMs - a.durationMs)[0]
  return [
    { title: '正确性覆盖', text: `${summary.count} 次运行中，${summary.evaluated} 次有明确正确性标签；${evaluated[0] ? `${evaluated[0].model} 的已评测正确率为 ${(evaluated[0].accuracy * 100).toFixed(1)}%（n=${evaluated[0].evaluated}）。` : '当前无法计算回答正确率。'}` },
    { title: '耗时分布', text: fastest ? `${fastest.model} 的耗时中位数为 ${(fastest.medianMs / 1000).toFixed(2)} s（n=${fastest.durationCount}）。总体耗时不能直接代表模型推理耗时。` : '缺少耗时记录，暂不能比较延迟。' },
    { title: '模型 × 任务 × 正确性', text: cells.length ? `已形成 ${cells.length} 个有评测标签的模型与任务组合。点击热力单元格，可以查看该组合的逐次结果和样本量。` : '补充任务及正确性标签后，可按模型和任务比较表现。' },
    { title: '模型 × 任务 × 资源', text: `散点图仅展示耗时与完整 Token 均已知的运行（${runs.filter((r) => r.durationMs != null && r.usageComplete && r.totalTokens != null).length} 次），颜色表示模型、形状表示任务、大小表示工具次数；点击可核对单次记录。` },
    { title: '异常与局限', text: slowest ? `当前最慢运行 ${slowest.runId} 用时 ${(slowest.durationMs / 1000).toFixed(2)} s，涉及 ${slowest.modelLabel} / ${slowest.taskName}。这只是当前样本的描述，需结合调用详情核查，不推断因果。` : '当前样本缺少耗时数据；缺失值、样本量与实验条件限制需要在报告中说明。', runKey: slowest?.key },
  ]
}

export function exportSummaryCsv(runs) {
  const columns = ['runId', 'taskId', 'taskName', 'model', 'modelVersion', 'conditionId', 'status', 'correct', 'basis', 'durationMs', 'totalTokens', 'usageComplete', 'toolCalls', 'errorType', 'demo', 'source']
  const cell = (value) => {
    let text = value == null ? '' : String(value)
    if (/^[=+\-@\t\r\n]/.test(text) || /^\s+[=+\-@]/.test(text)) text = `'${text}`
    return `"${text.replace(/"/g, '""')}"`
  }
  return '\uFEFF' + [columns.map(cell).join(','), ...runs.map((run) => columns.map((key) => cell(run[key])).join(','))].join('\r\n')
}
