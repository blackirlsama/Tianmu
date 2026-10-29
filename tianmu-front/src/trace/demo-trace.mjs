// Clearly labelled, synthetic examples for frontend development; not experiment results.
const start = BigInt(Date.parse('2026-10-29T10:24:31+08:00')) * 1000000n
const id = (n) => `10000000000000${String(n).padStart(2, '0')}`

function example(failed = false) {
  const traceId = failed ? 'b2e75df847f14db79df00f92b1ac2402' : 'a1e75df847f14db79df00f92b1ac2401'
  const runId = failed ? 'demo-tool-timeout' : 'demo-danmaku-query'
  const end = failed ? 1950 : 1420
  const span = (n, parent, name, type, kind, offset, duration, attributes = {}) => ({
    schemaVersion: '1.1', eventId: `${traceId.slice(0, 8)}-47f1-4db7-9df0-${String(n).padStart(12, '0')}`, serviceName: 'Tianmu-operations-agent', serviceVersion: 'demo',
    traceId, spanId: id(n), parentSpanId: parent ? id(parent) : '0000000000000000', name, kind: 'INTERNAL',
    startTimeUnixNano: String(start + BigInt(offset) * 1000000n),
    endTimeUnixNano: String(start + BigInt(offset + duration) * 1000000n), durationNanos: duration * 1000000,
    status: attributes['node.status'] === 'TIMED_OUT' ? 'ERROR' : 'UNSET',
    attributes: {
      'run.id': runId, 'span.type': type, 'node.type': kind,
      'node.status': 'SUCCEEDED', ...attributes,
    },
  })
  const spans = [
    span(1, 0, 'agent.run', 'agent_start', 'REQUEST', 0, end, {
      'run.status': failed ? 'FAILED' : 'SUCCEEDED', 'node.status': failed ? 'FAILED' : 'SUCCEEDED',
      'run.e2e_ms': end, 'usage.scope': 'RUN', 'usage.complete': true,
      'usage.input_tokens': failed ? 208 : 448, 'usage.output_tokens': failed ? 116 : 156,
    }),
    span(2, 1, 'agent.route', 'pre_route', 'ROUTING', 0, 280, { 'request.route': 'HARNESS', 'route.reason': 'READY' }),
    span(3, 2, 'query_understanding', 'query_understanding', 'MODEL', 30, 240, {
      'model.name': 'deepseek-chat', 'model.input_tokens': 80, 'model.output_tokens': 20, 'model.decision': 'PROCEED',
    }),
    span(4, 1, 'agent.loop', 'agent_loop', 'LOOP', 300, failed ? 1610 : 980, { 'node.status': failed ? 'FAILED' : 'SUCCEEDED' }),
    span(5, 4, 'agent.model', 'llm_inference', 'MODEL', 300, 420, {
      'model.name': 'deepseek-chat', 'model.input_tokens': 128, 'model.output_tokens': 96,
      'model.decision': 'CALL_TOOL', 'model.selected_tool': 'query_danmaku_count', 'loop.step': 1,
    }),
    span(6, 4, 'agent.tool', 'tool_call', 'TOOL', 740, failed ? 1150 : 130, {
      'tool.name': 'query_danmaku_count', 'tool.success': !failed, 'loop.step': 1,
      'node.status': failed ? 'TIMED_OUT' : 'SUCCEEDED', ...(failed ? { 'error.code': 'TOOL_TIMEOUT' } : {}),
    }),
    ...(!failed ? [span(7, 4, 'agent.model', 'llm_inference', 'MODEL', 890, 390, {
      'model.name': 'deepseek-chat', 'model.input_tokens': 240, 'model.output_tokens': 40,
      'model.decision': 'FINAL_ANSWER', 'loop.step': 2,
    })] : []),
    span(8, 1, failed ? 'error' : 'agent.deliver', failed ? 'error' : 'answer_ready', 'DELIVERY', failed ? 1910 : 1300, failed ? 40 : 100, {
      'node.status': failed ? 'FAILED' : 'SUCCEEDED', ...(failed ? { 'error.code': 'TOOL_TIMEOUT' } : { 'answer.evidence_count': 1 }),
    }),
  ]
  const details = {
    [id(1)]: { input: { message: '查询视频 1001 昨天的弹幕数', context: { videoId: '1001' } }, output: { status: failed ? 'FAILED' : 'SUCCEEDED', ...(failed ? { error: 'TOOL_TIMEOUT' } : { answer: '视频 1001 昨天共有 128 条已入库弹幕。' }) } },
    [id(2)]: { input: { message: '查询视频 1001 昨天的弹幕数' }, output: { route: 'HARNESS', reason: 'READY', businessDate: '2026-10-28' } },
    [id(3)]: { input: { query: '查询视频 1001 昨天的弹幕数', timezone: 'Asia/Shanghai' }, output: { decision: 'PROCEED', videoId: '1001', date: '2026-10-28' } },
    [id(4)]: { input: { availableTools: ['query_danmaku_count'], maxTurns: 8 }, output: { turns: failed ? 1 : 2, outcome: failed ? 'FAILED' : 'FINAL_ANSWER' } },
    [id(5)]: { input: { messages: [{ role: 'user', content: '查询视频 1001 昨天的弹幕数' }], model: 'deepseek-chat' }, output: { decision: 'CALL_TOOL', tool: 'query_danmaku_count', arguments: { videoId: '1001', date: '2026-10-28' } } },
    [id(6)]: { input: { videoId: '1001', date: '2026-10-28' }, output: failed ? { success: false, error: { code: 'TOOL_TIMEOUT', message: '演示：只读查询超时' } } : { success: true, count: 128, evidenceId: 'evidence-demo-001', scope: '当前已入库且仍保留的弹幕' } },
    [id(7)]: { input: { observation: { count: 128, evidenceId: 'evidence-demo-001' } }, output: { decision: 'FINAL_ANSWER', answer: '视频 1001 昨天共有 128 条已入库弹幕。', evidenceIds: ['evidence-demo-001'] } },
    [id(8)]: { input: failed ? { error: 'TOOL_TIMEOUT' } : { answer: '视频 1001 昨天共有 128 条已入库弹幕。', evidenceIds: ['evidence-demo-001'] }, output: { delivered: true, type: failed ? 'ERROR' : 'ANSWER' } },
  }
  return { demo: true, title: failed ? '弹幕查询 · 工具超时' : '弹幕查询 · 正常完成', spans, details }
}

export const demoFiles = [example(), example(true)]
