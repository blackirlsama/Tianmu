import { readStoredUser } from '@/lib/auth'
import { useUserStore } from '@/store/user'
import { reportPath, sseDecoder, validRunId } from '@/agent/protocol.mjs'

export function agentUser() {
  return readStoredUser()
}

function authorization() {
  const token = agentUser().token
  if (!token || typeof token !== 'string')
    throw new Error('请先登录网站后再使用 AI 分析')
  return token.startsWith('Bearer ') ? token : `Bearer ${token}`
}

async function request(path, options = {}) {
  const headers = { Authorization: authorization(), ...options.headers }
  let response
  try {
    response = await fetch(path, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
      headers,
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new Error('无法连接分析服务，请检查网络后重试')
  }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    if (response.status === 401) useUserStore().logout()
    const messages = {
      UNAUTHORIZED: '登录已失效，请重新登录',
      AUTH_CONTEXT_EXPIRED: '登录已失效，请重新登录',
      FORBIDDEN: '你没有权限分析这个视频或直播间',
      RUN_NOT_FOUND: '任务不存在或已过期',
      RESULT_NOT_FOUND: '报告不存在或已过期',
      DATE_OUT_OF_RANGE: '请选择最近 30 天内已经结束的时段',
      CAPACITY_EXCEEDED: '当前分析任务较多，请稍后重试',
      EVENTS_EXPIRED: '进度记录已过期，正在同步任务状态',
      QA_NOT_FOUND: '这条常见问答不存在',
      INVALID_DRAFT: '请检查草稿：内容不能空白或重复，弹幕时间须在视频范围内',
      DRAFT_CONFLICT: '草稿状态已变化，请刷新；已开始发送的内容不能修改',
      DRAFT_NOT_READY: '草稿尚未生成完成',
      DRAFT_NOT_FOUND: '草稿不存在或已过期',
      PUBLICATION_UNAVAILABLE: '发送服务暂不可用，请稍后重试确认发送结果',
      QA_CONFLICT: '这条问答已经更新，请刷新后再操作',
      QA_UNAVAILABLE: '常见问答暂时不可用，请稍后重试',
    }
    const error = new Error(
      messages[data.code] ||
        (response.status === 401
          ? '登录已失效，请重新登录'
          : '暂时没能完成请求，请稍后重试')
    )
    error.code = data.code
    error.status = response.status
    throw error
  }
  return response
}

function runPath(id) {
  if (!validRunId(id)) throw new Error('无效的任务 ID')
  return `/api/agent/runs/${id}`
}

export async function getQaCatalog(signal) {
  return (await request('/api/agent/qa', { signal })).json()
}

export async function changeQaEntry(id, version, reset, signal) {
  if (!/^[a-f0-9]{64}$/.test(id)) throw new Error('无效的问答 ID')
  return (await request(`/api/agent/qa/${id}/${reset ? 'reset' : 'disable'}`, {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ version }),
  })).json()
}

export async function checkCreatorAccess(videoId, signal) {
  if (typeof videoId !== 'string' || !/^[1-9]\d{0,18}$/.test(videoId) ||
      (videoId.length === 19 && videoId > '9223372036854775807')) throw new Error('视频 ID 无效')
  return (await request(`/api/agent/videos/${videoId}/access`, { signal })).json()
}

export async function getRoomSummary(videoId, start, signal) {
  if (!/^[1-9]\d{0,18}$/.test(videoId) || (videoId.length === 19 && videoId > '9223372036854775807'))
    throw new Error('视频 ID 无效')
  const query = start ? `?start=${encodeURIComponent(start)}` : ''
  const response = await request(`/api/agent/rooms/${videoId}/summary${query}`, { signal })
  return response.json()
}

export async function createAgentRun(body, key, signal) {
  const response = await request('/api/agent/runs', {
    method: 'POST',
    signal,
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(body),
  })
  return response.json()
}
export async function getAgentRun(id, signal) {
  return (await request(runPath(id), { signal })).json()
}
export async function getCreatorDraft(id, signal) {
  const response = await request(`${runPath(id)}/creator-draft`, { signal })
  const body = await response.text()
  return body ? JSON.parse(body) : null
}
export async function confirmCreatorDraft(id, items, signal) {
  return (await request(`${runPath(id)}/creator-draft/confirm`, {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items }),
  })).json()
}
export async function cancelAgentRun(id, signal) {
  return (
    await request(`${runPath(id)}/cancel`, { method: 'POST', signal })
  ).json()
}

export async function readAgentEvents(id, after, signal, consume) {
  const response = await request(`${runPath(id)}/events`, {
    signal,
    headers: { Accept: 'text/event-stream', 'Last-Event-ID': String(after) },
  })
  if (
    !response.headers.get('content-type')?.includes('text/event-stream') ||
    !response.body
  )
    throw new Error('进度连接不可用，正在同步任务状态')
  const reader = response.body.getReader(),
    decoder = new TextDecoder()
  const parser = sseDecoder(consume)
  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) {
        parser.push(decoder.decode())
        break
      }
      parser.push(decoder.decode(value, { stream: true }))
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export async function getAgentReport(runId, path, signal) {
  if (!reportPath(runId, path)) throw new Error('无效的报告链接')
  const response = await request(path, { signal })
  if (!response.headers.get('content-type')?.includes('text/html'))
    throw new Error('报告格式不正确')
  return response.text()
}
