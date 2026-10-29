// Adapt the existing TraceEnvelope export without depending on a running backend.
const STATUS_LABELS = {
  SUCCEEDED: '成功', FAILED: '失败', TIMED_OUT: '超时', CANCELLED: '已取消',
  RUNNING: '运行中', UNKNOWN: '未知', NEEDS_INPUT: '待补充',
}

export const statusLabel = (status) => STATUS_LABELS[status] || status || '未知'
export const formatDuration = (ms) => ms == null ? '未提供' : ms < 1000 ? `${Number(ms.toFixed(2))} ms` : `${(ms / 1000).toFixed(2)} s`
export const formatValue = (value) => value == null ? '未提供' : value.toLocaleString('zh-CN')

function finite(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
}

function nanos(value) {
  if (value == null || value === '') return null
  try {
    const result = BigInt(typeof value === 'number' ? Math.trunc(value) : value)
    return result >= 0 ? result : null
  } catch { return null }
}

function nodeStatus(span) {
  const attrs = span.attributes || {}
  if (attrs['node.status']) return attrs['node.status']
  if (attrs['run.status']) return attrs['run.status']
  if (span.status === 'ERROR') return 'FAILED'
  if (span.status === 'OK') return 'SUCCEEDED'
  return 'UNKNOWN' // OTel UNSET does not mean business success.
}

export function parseTraceFile(text, sourceName = '导入日志') {
  let payload
  try {
    payload = JSON.parse(text)
  } catch {
    try { payload = text.split(/\r?\n/).filter((line) => line.trim()).map((line) => JSON.parse(line)) }
    catch { throw new Error('文件解析失败，请导入有效的 JSON 或逐行 JSONL 日志。') }
  }
  const records = Array.isArray(payload) ? payload : payload?.spans || (payload?.spanId ? [payload] : null)
  if (!Array.isArray(records) || !records.length) throw new Error('日志中没有找到 Span 节点。')
  if (records.length > 2000) throw new Error('第一版单次最多支持 2,000 条 Span，请按运行拆分后导入。')
  const groups = new Map()
  for (const span of records) {
    if (!span || typeof span.spanId !== 'string' || !span.spanId || typeof span.traceId !== 'string' || !span.traceId || typeof span.name !== 'string') {
      throw new Error('节点缺少 spanId、traceId 或 name，请使用 Tianmu TraceEnvelope 格式。')
    }
    if (span.attributes != null && (typeof span.attributes !== 'object' || Array.isArray(span.attributes))) {
      throw new Error('节点 attributes 必须是对象。')
    }
    if (!groups.has(span.traceId)) groups.set(span.traceId, new Map())
    groups.get(span.traceId).set(span.spanId, span) // Export replay can repeat the same node.
  }
  return [...groups.entries()].map(([traceId, spans]) => normalizeTrace(
    [...spans.values()], traceId, {
      sourceName, demo: payload.demo === true,
      title: payload.title, details: payload.details || {},
    },
  ))
}

function normalizeTrace(spans, traceId, meta) {
  const starts = spans.map((s) => nanos(s.startTimeUnixNano)).filter((n) => n != null)
  const ends = spans.map((s) => nanos(s.endTimeUnixNano)).filter((n) => n != null)
  const start = starts.length ? starts.reduce((a, b) => a < b ? a : b) : null
  const end = ends.length ? ends.reduce((a, b) => a > b ? a : b) : null
  const root = spans.find((s) => s.name === 'agent.run') || spans.find((s) => !spans.some((p) => p.spanId === s.parentSpanId)) || spans[0]
  const finish = spans.find((s) => s.attributes?.['span.type'] === 'finish')
  const summary = [finish, root].find((s) => s?.attributes?.['usage.scope'] === 'RUN')
  const attrs = root.attributes || {}
  const nodes = spans.map((span) => {
    const a = span.attributes || {}
    const ns = nanos(span.startTimeUnixNano)
    const stop = nanos(span.endTimeUnixNano)
    const duration = nanos(span.durationNanos) ?? (ns != null && stop != null && stop >= ns ? stop - ns : null)
    const inline = meta.details[span.spanId] || {}
    const inputTokens = finite(a['model.input_tokens'])
    const outputTokens = finite(a['model.output_tokens'])
    return {
      id: span.spanId, parentId: span.parentSpanId, name: span.name,
      type: a['span.type'] || span.name, kind: a['node.type'] || 'STAGE',
      label: a['span.type'] || span.name,
      model: a['model.name'] || null, tool: a['tool.name'] || null,
      status: nodeStatus(span), durationMs: duration == null ? null : Number(duration) / 1e6,
      offsetMs: start != null && ns != null ? Number(ns - start) / 1e6 : null,
      startMs: ns == null ? null : Number(ns) / 1e6,
      inputTokens, outputTokens,
      totalTokens: finite(a['model.total_tokens']) ?? (inputTokens != null && outputTokens != null ? inputTokens + outputTokens : null),
      turn: a['loop.step'] ?? null,
      input: inline.input ?? span.input ?? null, output: inline.output ?? span.output ?? null,
      inputState: a['detail.input_state'] || (inline.input != null || span.input != null ? 'COMPLETE' : 'NOT_CAPTURED'),
      outputState: a['detail.output_state'] || (inline.output != null || span.output != null ? 'COMPLETE' : 'NOT_CAPTURED'),
      inputRef: a['detail.input_ref'], outputRef: a['detail.output_ref'],
      attributes: a,
    }
  }).sort((a, b) => (a.offsetMs ?? Infinity) - (b.offsetMs ?? Infinity))

  // Reject cycles rather than invent a hierarchy or recurse indefinitely.
  const byId = new Map(nodes.map((node) => [node.id, node]))
  for (const node of nodes) {
    const seen = new Set([node.id])
    let parent = byId.get(node.parentId)
    while (parent) {
      if (seen.has(parent.id)) throw new Error('日志的父子节点关系存在循环，请检查 parentSpanId。')
      seen.add(parent.id)
      parent = byId.get(parent.parentId)
    }
  }
  const modelNodes = nodes.filter((n) => n.kind === 'MODEL' || n.type === 'llm_inference' || n.model)
  const sumTokens = (key) => {
    if (summary) return finite(summary.attributes[`usage.${key}`])
    const provided = modelNodes.map((n) => n[key === 'input_tokens' ? 'inputTokens' : 'outputTokens']).filter((v) => v != null)
    return provided.length ? provided.reduce((a, b) => a + b, 0) : null
  }
  const inputTokens = sumTokens('input_tokens'), outputTokens = sumTokens('output_tokens')
  return {
    id: traceId, runId: attrs['run.id'] || nodes.find((n) => n.attributes['run.id'])?.attributes['run.id'] || traceId,
    title: meta.title || attrs['task.name'] || root.name,
    source: meta.sourceName, demo: meta.demo,
    model: [...new Set(modelNodes.map((n) => n.model).filter(Boolean))].join(' / ') || '未提供',
    status: finish?.attributes?.['run.status'] || attrs['run.status'] || root.attributes?.['node.status'] || nodeStatus(root),
    durationMs: finite(finish?.attributes?.['run.e2e_ms']) ?? finite(attrs['run.e2e_ms']) ?? (start != null && end != null ? Number(end - start) / 1e6 : null),
    extentMs: start != null && end != null ? Number(end - start) / 1e6 : 0,
    startMs: start == null ? null : Number(start) / 1e6,
    endMs: end == null ? null : Number(end) / 1e6,
    inputTokens, outputTokens,
    totalTokens: inputTokens != null && outputTokens != null ? inputTokens + outputTokens : null,
    usageComplete: summary ? summary.attributes['usage.complete'] === true : modelNodes.length > 0 && modelNodes.every((n) => n.inputTokens != null && n.outputTokens != null),
    nodes,
  }
}

export function layoutTrace(nodes) {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const children = new Map(nodes.map((node) => [node.id, []]))
  const roots = []
  for (const node of nodes) {
    if (byId.has(node.parentId)) children.get(node.parentId).push(node)
    else roots.push(node)
  }
  const positions = new Map()
  let row = 0
  function visit(node, depth) {
    const nested = children.get(node.id)
    const rows = nested.map((child) => visit(child, depth + 1))
    const y = rows.length ? (rows[0] + rows[rows.length - 1]) / 2 : row++ * 90
    positions.set(node.id, { x: depth * 255, y })
    return y
  }
  roots.forEach((root) => { visit(root, 0); row += 0.5 })
  return nodes.map((node) => ({ id: node.id, type: 'trace', position: positions.get(node.id), data: node }))
}
