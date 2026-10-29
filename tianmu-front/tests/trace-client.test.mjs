import test from 'node:test'
import assert from 'node:assert/strict'
import { createTraceClient, tracePayloadPath } from '../src/trace/trace-client.mjs'
const runId = 'a12994cd-8919-4a8f-bd31-170eab627745'
const nodeId = 'a12994cd89194a8f'
const reference = `${nodeId}/input/123/${'a'.repeat(64)}`

test('trace and payload reads use run-owned same-origin endpoints with authorization and no caching', async () => {
  const calls = []
  const client = createTraceClient({ getToken: () => 'test-session', fetchImpl: async (path, options) => {
    calls.push({ path, options }); return { ok: true, json: async () => ({ state: 'COMPLETE', value: { count: 128 } }) }
  } })
  const controller = new AbortController()
  await client.getTrace(runId, controller.signal)
  const payload = await client.getPayload(runId, nodeId, 'input', reference, controller.signal)
  assert.equal(calls[0].path, `/api/agent/runs/${runId}/trace`)
  assert.equal(calls[1].options.headers.Authorization, 'Bearer test-session')
  assert.equal(calls[1].options.signal, controller.signal)
  assert.equal(calls[1].options.cache, 'no-store')
  assert.equal(payload.value.count, 128)
})

test('reject cross-node references, traversal and unauthorized calls without generating fake data', async () => {
  assert.throws(() => tracePayloadPath(runId, nodeId, 'output', reference), /不一致/)
  assert.throws(() => tracePayloadPath(runId, 'b'.repeat(16), 'input', reference), /不一致/)
  assert.throws(() => tracePayloadPath(runId, nodeId, 'input', '../../other'), /不一致/)
  let calls = 0
  const client = createTraceClient({ getToken: () => '', fetchImpl: async () => { calls++ } })
  await assert.rejects(client.getTrace(runId), /先登录/)
  assert.equal(calls, 0)
  const denied = createTraceClient({ getToken: () => 'session', fetchImpl: async () => ({ ok: false, status: 403, json: async () => ({}) }) })
  await assert.rejects(denied.getTrace(runId), /没有查看/)
})

test('the deployed gateway model configuration gate is explained without a demo fallback', async () => {
  const client = createTraceClient({ getToken: () => 'session', fetchImpl: async () => ({ ok: false, status: 503,
    json: async () => ({ code: 503, message: 'AI 模型尚未配置，请联系部署管理员配置私有 API Key' }) }) })
  await assert.rejects(client.getTrace(runId), /服务器尚未配置真实模型/)
})
