const RUN_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const validTraceRunId = (value) => typeof value === 'string' && RUN_ID.test(value)

export function tracePayloadPath(runId, spanId, direction, reference) {
  if (!validTraceRunId(runId) || !/^[0-9a-f]{16}$/.test(spanId) || !['input', 'output'].includes(direction)) throw new Error('无效的节点详情参数')
  const match = /^([0-9a-f]{16})\/(input|output)\/([1-9]\d{0,5})\/([0-9a-f]{64})$/.exec(reference || '')
  if (!match || match[1] !== spanId || match[2] !== direction || Number(match[3]) > 524288) throw new Error('节点详情引用无效或与选中节点不一致')
  return `/api/agent/runs/${runId}/trace-payloads/${reference}`
}

export function createTraceClient({ getToken, fetchImpl = globalThis.fetch }) {
  async function request(path, options = {}) {
    const token = getToken()?.trim()
    if (!token) throw new Error('请先登录天幕网站，或明确选择本地 Demo 身份进行免 Key 联调。')
    let response
    try {
      response = await fetchImpl(path, { ...options, credentials: 'same-origin', cache: 'no-store', headers: {
        ...options.headers, Authorization: token.startsWith('Bearer ') ? token : `Bearer ${token}`,
      } })
    } catch (error) {
      if (error.name === 'AbortError') throw error
      throw new Error('无法连接 Agent 后端，请检查服务是否启动及 TIANMU_AGENT_PROXY_TARGET 配置。')
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      const messages = { 401: '登录态无效或已过期，请重新登录；本地 Demo 身份仅适用于默认 Demo 服务。',
        403: '当前账号没有查看该运行的权限。', 404: body.code === 'RUN_NOT_FOUND' ? '运行不存在、已过期或不属于当前账号。' : '后端尚未部署 Trace 查询接口，请更新并重启 Agent。',
        503: body.code === 'MODEL_NOT_CONFIGURED' || /AI\s*模型尚未配置/.test(body.message || '')
          ? '服务器尚未配置真实模型，Agent 服务未启用，请由部署管理员在服务端完成配置。'
          : '后端存储或业务服务暂不可用。', 429: '后端当前任务较多，请稍后重试。' }
      const error = new Error(messages[response.status] || '后端请求失败，请检查运行参数和服务日志。')
      error.status = response.status
      throw error
    }
    try { return await response.json() }
    catch { throw new Error('后端未返回有效 JSON，请检查代理配置和接口版本。') }
  }
  function runPath(runId) {
    if (!validTraceRunId(runId)) throw new Error('请输入 Agent 返回的有效运行 ID（UUID）。')
    return `/api/agent/runs/${runId}`
  }
  return {
    getTrace: (runId, signal) => request(`${runPath(runId)}/trace`, { signal }),
    createRun: (message, signal) => {
      if (!message?.trim() || message.length > 2000) throw new Error('请输入 1–2,000 字的提问。')
      return request('/api/agent/runs', { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message: message.trim() }) })
    },
    getPayload: (runId, nodeId, direction, reference, signal) => request(tracePayloadPath(runId, nodeId, direction, reference), { signal }),
  }
}
