import { validTraceRunId } from './trace-client.mjs'
import { parseTraceFile } from './trace-data.mjs'
import { normalizeEvaluationRun } from './evaluation-data.mjs'

export function parseRunIds(text) {
  const ids = [...new Set(text.split(/[\s,;，；]+/).filter(Boolean))]
  if (!ids.length || ids.length > 50) throw new Error('请输入 1–50 个运行 ID，每行一个。')
  if (ids.some((id) => !validTraceRunId(id))) throw new Error('运行 ID 格式无效，请填写 Agent 返回的 UUID。')
  return ids
}

export async function loadEvaluationRuns({ text, client, signal, conditionId = '', onProgress = () => {} }) {
  const ids = parseRunIds(text), runs = [], errors = []
  for (const id of ids) {
    if (signal.aborted) throw new DOMException('已取消读取', 'AbortError')
    try {
      const data = await client.getTrace(id, signal)
      if (signal.aborted) throw new DOMException('已取消读取', 'AbortError')
      if (data.run?.runId !== id) throw new Error('后端返回的运行 ID 与请求不一致。')
      if (!['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'NEEDS_INPUT'].includes(data.run.status)) throw new Error('运行尚未结束，请等待终态后再读取。')
      if (data.truncated) throw new Error('后端日志已截断；请先导出完整日志，避免把不完整记录计入比较。')
      if (!data.spans?.length) throw new Error('没有可用的已结束节点，可能尚在执行、已过期或位于其他实例。')
      const parsed = parseTraceFile(JSON.stringify({ spans: data.spans }), 'Agent 批量运行')
      const trace = parsed.find((item) => item.id === data.run.traceId) || parsed.find((item) => item.nodes.some((node) => node.name === 'agent.run')) || parsed[0]
      if (!trace.nodes.some((node) => node.name === 'agent.run')) throw new Error('运行根日志尚未到齐，请稍后读取或导出完整日志。')
      if (!trace.nodes.some((node) => node.type === 'llm_inference')) throw new Error('没有主循环模型调用，可能是 FAQ 直答；不计入模型实验。')
      if (new Set(data.spans.map((span) => span.attributes?.['run.id']).filter(Boolean)).size !== 1 || trace.runId !== id) throw new Error('日志包含其他运行，未加入对比。')
      trace.live = true
      trace.status = data.run.status
      trace.modelMode = data.modelMode
      trace.businessMode = data.businessMode
      trace.rawTrace = { spans: data.spans.filter((span) => span.traceId === trace.id), details: {} }
      const row = normalizeEvaluationRun({ runId: id, normalizedTrace: trace, taskId: data.run.task?.specification?.type,
        conditionId, modelMode: data.modelMode, businessMode: data.businessMode,
        demo: data.modelMode === 'DEMO', status: data.run.status,
      }, { source: 'Agent 后端批量读取', demo: data.modelMode === 'DEMO' })
      if (data.modelMode !== 'PROVIDER' && data.modelMode !== 'DEMO') throw new Error('后端未声明模型来源，无法确认真实模型身份。')
      if (runs.length && runs[0].demo !== row.demo) throw new Error('模型来源发生变化，不能混合模拟与提供方运行。')
      runs.push(row)
    } catch (error) {
      if (error.name === 'AbortError' || [401, 403].includes(error.status)) throw error
      errors.push({ runId: id, message: error.message })
    }
    onProgress({ completed: runs.length + errors.length, total: ids.length })
  }
  return { dataset: runs.length ? { title: 'Agent 已采集运行', source: '后端批量读取', demo: runs.every((row) => row.demo), runs } : null, errors }
}
