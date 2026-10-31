<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TraceGraph from '@/components/trace/TraceGraph.vue'
import TraceIcon from '@/components/trace/TraceIcon.vue'
import TraceCode from '@/components/trace/TraceCode.vue'
import { demoFiles } from '@/trace/demo-trace.mjs'
import { parseTraceFile, formatDuration, formatValue, statusLabel } from '@/trace/trace-data.mjs'
import { createTraceClient, validTraceRunId } from '@/trace/trace-client.mjs'
import { readStoredUser } from '@/lib/auth'
import { useUserStore } from '@/store/user'
import { evaluationStore, addEvaluationRun, setEvaluationDataset } from '@/trace/evaluation-store'
import { normalizeEvaluationRun, parseEvaluationFile } from '@/trace/evaluation-data.mjs'

const route = useRoute(), router = useRouter()
const compareTask = ref(''), compareModel = ref(''), compareCondition = ref(''), compareVerdict = ref(''), compareBasis = ref(''), compareNote = ref('')

const examples = demoFiles.flatMap((data) => parseTraceFile(JSON.stringify(data), '内置演示日志'))
const traces = ref([])
const activeId = ref('')
const selectedId = ref('')
const sourceMode = ref('backend')
const authMode = ref('website')
const runIdInput = ref(new URLSearchParams(window.location.search).get('runId') || '')
const question = ref('分析直播间1001昨天的弹幕并生成报告')
const backendLoading = ref(false)
const backendError = ref('')
const backendNote = ref('输入运行 ID，或向 Agent 发起提问，读取实际执行日志。')
const backendMode = ref('')
const detailLoading = ref(false)
const detailError = ref('')
let generation = 0, requestAbort, detailAbort, pollTimer
const detailCache = new Map()
const client = createTraceClient({ getToken: () => authMode.value === 'demo' ? 'demo-alice' : readStoredUser().token })
const mode = ref('graph')
const detailTab = ref('payload')
const fileInput = ref(null)
const importError = ref('')
const importing = ref(false)
const previousTitle = document.title
document.title = 'Trace Studio · 天幕运行追踪'
onBeforeUnmount(() => { stopBackend(); window.removeEventListener('storage', identityChanged); document.title = previousTitle })

const trace = computed(() => traces.value.find((item) => item.id === activeId.value) || traces.value[0])
const selected = computed(() => trace.value?.nodes.find((node) => node.id === selectedId.value))
const timingNodes = computed(() => (trace.value?.nodes || []).filter((node) => trace.value.nodes.length > 12 ? ['MODEL', 'TOOL', 'DELIVERY'].includes(node.kind) : !['REQUEST', 'LOOP'].includes(node.kind)))
const toolCount = computed(() => (trace.value?.nodes || []).filter((node) => node.type === 'tool_call' || node.kind === 'TOOL').length)
const modelCount = computed(() => (trace.value?.nodes || []).filter((node) => node.kind === 'MODEL').length)
const share = computed(() => selected.value?.durationMs != null && trace.value?.durationMs > 0 ? `${(selected.value.durationMs / trace.value.durationMs * 100).toFixed(1)}%` : '未提供')
const sourceLabel = computed(() => sourceMode.value === 'backend' ? '后端运行日志' : trace.value?.demo ? '演示数据' : '已导入日志')
const kindLabel = (node) => ({ MODEL: '模型调用', TOOL: '工具调用', REQUEST: '运行入口', ROUTING: '请求路由', LOOP: '执行循环', DELIVERY: '结果交付' }[node.kind] || '执行阶段')
const iconFor = (node) => node.kind === 'MODEL' ? 'model' : node.kind === 'TOOL' ? 'tool' : 'stage'
const clock = (value) => value == null ? '未提供' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
const date = (value) => value == null ? '时间未提供' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))

function selectNode(id) { selectedId.value = id }
function changeTrace() {
  selectedId.value = trace.value.nodes.find((node) => node.type === 'llm_inference')?.id || trace.value.nodes[0].id
}
function stopBackend() {
  generation++
  clearTimeout(pollTimer)
  requestAbort?.abort()
  detailAbort?.abort()
  detailCache.clear()
  backendLoading.value = false
  detailLoading.value = false
  detailError.value = ''
}
function identityChanged() {
  if (sourceMode.value !== 'backend') return
  stopBackend()
  traces.value = []
  setEvaluationDataset(null)
  backendMode.value = ''
  backendNote.value = '身份已变化，请重新加载运行。'
}
window.addEventListener('storage', identityChanged)
watch(authMode, identityChanged)
watch(() => useUserStore().user?.token, () => {
  stopBackend()
  traces.value = []
  sourceMode.value = 'backend'
  backendMode.value = ''
  backendNote.value = '网站身份已变化，请重新加载已授权运行。'
})
function showEvaluationRun() {
  const key = route.query.evaluationRun
  if (!key) return false
  const run = evaluationStore.dataset?.runs.find((item) => item.key === key)
  if (!run?.trace) {
    backendError.value = '这次运行的数据已不在当前标签页内存中，请返回模型对比页重新导入。'
    return true
  }
  compareTask.value = run.taskId
  compareModel.value = run.model
  compareCondition.value = run.conditionId === '条件未记录' ? '' : run.conditionId
  compareVerdict.value = run.correct == null ? '' : run.correct ? 'true' : 'false'
  compareBasis.value = run.basis
  if (run.trace.live) {
    sourceMode.value = 'file'
    runIdInput.value = run.runId
    authMode.value = run.trace.authMode || 'website'
    nextTick(() => loadBackend())
    return true
  }
  stopBackend()
  sourceMode.value = run.demo ? 'demo' : 'file'
  traces.value = [run.trace]
  activeId.value = run.trace.id
  backendMode.value = ''
  backendNote.value = `来自模型对比：${run.modelLabel} / ${run.taskName}`
  changeTrace()
  return true
}
onMounted(() => { if (!showEvaluationRun() && validTraceRunId(runIdInput.value) && readStoredUser().token) loadBackend() })
watch(() => route.query.evaluationRun, showEvaluationRun)
function addToComparison() {
  compareNote.value = ''
  if (!trace.value) return
  if (compareVerdict.value && !compareBasis.value.trim()) { compareNote.value = '设置正确性标签时，请填写评测依据。'; return }
  const previous = evaluationStore.dataset?.runs.find((row) => row.runId === trace.value.runId)
  const run = normalizeEvaluationRun({ runId: trace.value.runId, normalizedTrace: trace.value, taskId: compareTask.value,
    taskName: previous?.taskId === compareTask.value ? previous.taskName : compareTask.value,
    modelVersion: previous?.model === compareModel.value ? previous.modelVersion : undefined,
    model: compareModel.value || undefined, conditionId: compareCondition.value,
    evaluation: { correct: compareVerdict.value === '' ? null : compareVerdict.value === 'true', basis: compareBasis.value },
    demo: trace.value.demo, modelMode: trace.value.modelMode, businessMode: trace.value.businessMode,
  }, { source: trace.value.source, demo: trace.value.demo })
  addEvaluationRun(run)
  router.push('/analysis')
}

async function loadBackend(create = false) {
  stopBackend()
  const current = generation
  requestAbort = new AbortController()
  const signal = requestAbort.signal
  sourceMode.value = 'backend'
  traces.value = []
  activeId.value = ''
  backendLoading.value = true
  backendError.value = ''
  importError.value = ''
  backendMode.value = ''
  backendNote.value = create ? '正在向 Agent 提交提问…' : '正在读取后端运行日志…'
  try {
    let id = runIdInput.value.trim()
    if (create) {
      const run = await client.createRun(question.value, signal)
      if (current !== generation) return
      id = run.runId
      runIdInput.value = id
    }
    if (!validTraceRunId(id)) throw new Error('请输入 Agent 返回的有效运行 ID（UUID）。')
    let terminalReads = 0
    const poll = async () => {
      try {
        const data = await client.getTrace(id, signal)
        if (current !== generation) return
        backendMode.value = `${data.modelMode === 'PROVIDER' ? '真实模型提供方' : '模拟模型'} / ${data.businessMode === 'BUSINESS' ? '业务数据' : '模拟业务数据'}`
        backendNote.value = `运行 ${id} · ${statusLabel(data.run.status)} · ${data.spans.length} 个已结束节点${data.truncated ? ' · 记录受查询上限限制' : ''}`
        if (data.spans.length) {
          const normalized = parseTraceFile(JSON.stringify({ spans: data.spans, title: `运行 ${id.slice(0, 8)}` }), `Agent 后端 · ${data.source === 'archive' ? '文件归档' : '近期运行'}`)
          for (const item of normalized) {
            item.live = true
            item.runId = id
            item.status = data.run.status
            item.modelMode = data.modelMode
            item.businessMode = data.businessMode
            item.authMode = authMode.value
            item.rawTrace = { spans: data.spans, details: {} }
            item.nodes.forEach((node) => {
              for (const direction of ['input', 'output']) {
                const detail = detailCache.get(`${id}/${node[`${direction}Ref`]}`)
                if (detail) { node[direction] = detail.value; node[`${direction}State`] = detail.state }
              }
            })
          }
          const previousNode = selectedId.value
          const previousTrace = activeId.value
          traces.value = normalized
          if (!compareTask.value) compareTask.value = data.run.task?.specification?.type || ''
          activeId.value = normalized.some((item) => item.id === previousTrace) ? previousTrace : normalized.find((item) => item.id === data.run.traceId)?.id || normalized[0].id
          if (!trace.value.nodes.some((node) => node.id === previousNode)) changeTrace()
        }
        backendLoading.value = false
        const terminal = ['SUCCEEDED', 'FAILED', 'NEEDS_INPUT', 'CANCELLED'].includes(data.run.status)
        terminalReads = terminal ? terminalReads + 1 : 0
        // Terminal notification can precede span cleanup; collect late records as well.
        if (terminalReads < 3) pollTimer = setTimeout(poll, 1200)
        else if (!data.spans.length) backendNote.value += '。此实例没有可查询节点，请检查保留期、服务实例或归档配置。'
      } catch (error) { fail(error) }
    }
    const fail = (error) => {
      if (current !== generation || error.name === 'AbortError') return
      backendLoading.value = false
      backendError.value = error.message
      if ([401, 403, 404].includes(error.status)) { traces.value = []; detailCache.clear() }
    }
    await poll()
  } catch (error) {
    if (current !== generation || error.name === 'AbortError') return
    backendLoading.value = false
    backendError.value = error.message
  }
}

async function loadSelectedDetails() {
  detailAbort?.abort()
  detailError.value = ''
  detailLoading.value = false
  const node = selected.value
  if (sourceMode.value !== 'backend' || !node || !trace.value?.live) return
  const current = generation, runId = trace.value.runId, nodeId = node.id
  detailAbort = new AbortController()
  const signal = detailAbort.signal
  detailLoading.value = true
  await Promise.all(['input', 'output'].map(async (direction) => {
    const reference = node[`${direction}Ref`]
    if (!reference || reference === 'UNAVAILABLE') return
    try {
      let detail = detailCache.get(`${runId}/${reference}`)
      if (!detail) {
        const payload = await client.getPayload(runId, nodeId, direction, reference, signal)
        if (current !== generation || signal.aborted) return
        detail = { value: payload.state === 'TRUNCATED' ? payload.preview : payload.value, state: payload.state }
        detailCache.set(`${runId}/${reference}`, detail)
      }
      if (current === generation && selected.value?.id === nodeId && trace.value?.runId === runId && !signal.aborted) {
        selected.value[direction] = detail.value
        selected.value[`${direction}State`] = detail.state
      }
    } catch (error) {
      if (current !== generation || signal.aborted || selected.value?.id !== nodeId) return
      detailError.value = error.message
      if ([401, 403].includes(error.status)) { backendError.value = error.message; traces.value = []; detailCache.clear(); requestAbort?.abort(); clearTimeout(pollTimer) }
    }
  }))
  if (current === generation && !signal.aborted) detailLoading.value = false
}
watch(() => [sourceMode.value, activeId.value, selectedId.value, selected.value?.inputRef, selected.value?.outputRef].join('|'), loadSelectedDetails)
function timingStyle(node) {
  const extent = trace.value.extentMs || 1
  return { left: `${Math.min(100, Math.max(0, (node.offsetMs || 0) / extent * 100))}%`, width: `${Math.min(100, Math.max(0.5, (node.durationMs || 0) / extent * 100))}%` }
}
async function importFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  importing.value = true
  importError.value = ''
  try {
    if (file.size > 10 * 1024 * 1024) throw new Error('文件超过 10 MB，请按运行拆分后导入。')
    const dataset = parseEvaluationFile(await file.text(), file.name)
    const imported = dataset.runs.map((run) => run.trace).filter(Boolean)
    if (!imported.length) { setEvaluationDataset(dataset); router.push('/analysis'); return }
    setEvaluationDataset(dataset)
    stopBackend()
    sourceMode.value = 'file'
    backendMode.value = ''
    backendNote.value = '当前查看本地导入文件，可在此重新连接后端。'
    traces.value = imported
    activeId.value = imported[0].id
    changeTrace()
  } catch (error) { importError.value = error.message }
  finally { importing.value = false; event.target.value = '' }
}
function resetDemo() {
  stopBackend()
  sourceMode.value = 'demo'
  backendMode.value = ''
  backendError.value = ''
  backendNote.value = '当前查看合成演示数据，可在此加载后端运行。'
  traces.value = examples
  activeId.value = examples[0].id
  importError.value = ''
  changeTrace()
}
function downloadExample() {
  const link = document.createElement('a')
  const url = URL.createObjectURL(new Blob([JSON.stringify(demoFiles[0], null, 2)], { type: 'application/json' }))
  link.href = url
  link.download = 'tianmu-trace-demo.json'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <div class="trace-studio">
    <header class="studio-topbar">
      <a class="studio-brand" href="/trace" aria-label="天幕 Trace Studio"><span class="brand-symbol"><TraceIcon name="trace" :size="22" /></span><strong>天幕<span>TIANMU</span></strong></a>
      <span class="topbar-separator" />
      <span class="studio-product">Trace Studio</span><span class="version-tag">BETA</span>
      <div class="topbar-right"><router-link to="/analysis" class="comparison-nav-link"><TraceIcon name="compare" :size="15" />模型对比总览</router-link><span class="local-status"><i />当前工作区</span><span class="avatar">T</span></div>
    </header>

    <main class="studio-main">
      <div class="workspace-heading">
        <div><div class="workspace-eyebrow">OBSERVABILITY <span>/</span> AGENT TRACE</div><h1>运行追踪<span>看见每一步，让执行过程清晰可循。</span></h1></div>
        <div class="heading-actions"><span class="source-badge" :class="{ imported: sourceMode !== 'demo' }"><i />{{ sourceLabel }}</span><button class="import-button" type="button" :disabled="importing" @click="fileInput.click()"><TraceIcon name="upload" :size="15" />{{ importing ? '正在导入…' : '导入日志' }}</button><input ref="fileInput" class="file-input" type="file" accept=".json,.jsonl,application/json" aria-label="选择 Trace 日志" @change="importFile" /></div>
      </div>
      <div v-if="importError" class="import-error" role="alert"><TraceIcon name="info" :size="16" />{{ importError }}</div>

      <section class="backend-connection studio-panel">
        <div class="connection-title"><h2><TraceIcon name="trace" :size="16" />连接 Agent 后端</h2><span>/api/agent · 已授权运行</span><button type="button" @click="resetDemo">查看界面演示</button></div>
        <div class="connection-controls"><label>访问身份<select v-model="authMode" aria-label="后端访问身份"><option value="website">使用天幕网站登录态</option><option value="demo">本地免 Key 联调（demo-alice）</option></select></label><label class="run-id-field">运行 ID<input v-model="runIdInput" placeholder="粘贴 Agent 返回的 runId（UUID）" aria-label="后端运行 ID" /></label><button class="import-button" type="button" :disabled="backendLoading" @click="loadBackend(false)">{{ backendLoading ? '正在连接…' : '加载 / 刷新运行' }}</button></div>
        <div class="connection-create"><input v-model="question" maxlength="2000" aria-label="发送给 Agent 的提问" placeholder="输入提问，执行后查看这次运行的实际 Trace" /><button class="import-button" type="button" :disabled="backendLoading || !question.trim()" @click="loadBackend(true)">执行并追踪<TraceIcon name="arrow" :size="14" /></button></div>
        <p class="connection-note" role="status">{{ backendNote }}<strong v-if="backendMode">服务模式：{{ backendMode }}</strong></p>
        <p v-if="authMode === 'demo'" class="connection-warning">本地联调会执行真正的 HTTP 请求和 Agent 流程，但默认使用模拟模型、模拟业务数据，不计入真实模型对比实验。</p>
        <p v-if="backendError" class="connection-error" role="alert">{{ backendError }}</p>
      </section>

      <template v-if="trace">
      <section class="trace-comparison-controls studio-panel"><div class="comparison-controls-title"><h2><TraceIcon name="compare" :size="15" />将这次运行加入模型对比</h2><span>仅在当前标签页保存评测标签</span></div><div class="comparison-controls-fields"><label>任务编号<input v-model="compareTask" placeholder="同一任务使用相同编号" aria-label="对比任务编号" maxlength="160" /></label><label>对比模型<input v-model="compareModel" :placeholder="trace.model" aria-label="对比模型名称" maxlength="160" /></label><label>统一条件编号<input v-model="compareCondition" placeholder="相同提示、工具、预算" aria-label="对比条件编号" maxlength="160" /></label><label>回答评测<select v-model="compareVerdict" aria-label="添加运行的评测标签"><option value="">未评测</option><option value="true">正确</option><option value="false">不正确</option></select></label><button class="import-button" type="button" @click="addToComparison">加入对比<TraceIcon name="arrow" :size="14" /></button></div><label v-if="compareVerdict" class="comparison-basis-label">评测依据<input v-model="compareBasis" placeholder="例如：与数据库对账一致，或人工标注规则 v1" aria-label="评测依据" maxlength="160" /></label><p v-if="compareNote" class="connection-error" role="alert">{{ compareNote }}</p></section>
      <div class="trace-toolbar">
        <div class="trace-breadcrumb"><TraceIcon name="trace" :size="15" /><span>Traces</span><TraceIcon name="chevron" :size="12" /><strong>{{ trace.runId }}</strong><span class="status-pill" :class="trace.status.toLowerCase()"><i />{{ statusLabel(trace.status) }}</span></div>
        <div class="toolbar-metrics"><span><TraceIcon name="clock" :size="14" /><em>总耗时</em><b>{{ formatDuration(trace.durationMs) }}</b></span><span><TraceIcon name="token" :size="14" /><em>Tokens</em><b>{{ formatValue(trace.totalTokens) }}</b><small v-if="!trace.usageComplete">部分 / 缺失</small></span></div>
      </div>

      <div class="studio-layout">
        <aside class="metadata-column">
          <section class="metadata-card studio-panel">
            <div class="section-eyebrow">TRACE METADATA</div><h2>运行信息</h2>
            <label class="trace-select-label" for="trace-select">当前运行</label>
            <div class="trace-select-wrap"><select id="trace-select" v-model="activeId" @change="changeTrace"><option v-for="item in traces" :key="item.id" :value="item.id">{{ item.title }}</option></select><TraceIcon name="down" :size="14" /></div>
            <dl class="metadata-list"><div><dt>模型</dt><dd class="model-label">{{ trace.model }}</dd></div><div><dt>类型</dt><dd>Agent run</dd></div><div><dt>状态</dt><dd :class="`text-${trace.status.toLowerCase()}`"><i class="small-status" />{{ statusLabel(trace.status) }}</dd></div><div><dt>开始时间</dt><dd>{{ clock(trace.startMs) }}</dd></div><div><dt>结束时间</dt><dd>{{ clock(trace.endMs) }}</dd></div></dl>
            <div class="run-range"><span /><i /><span /><i /><span /></div><div class="range-labels"><span>{{ clock(trace.startMs) }}</span><span>{{ clock(trace.endMs) }}</span></div>
            <div class="run-date">{{ date(trace.startMs) }} <span>UTC+08:00</span></div>
            <div class="metadata-divider" /><div class="call-totals"><div><strong>{{ modelCount }}</strong><span>模型调用</span></div><div><strong>{{ toolCount }}</strong><span>工具调用</span></div><div><strong>{{ trace.nodes.length }}</strong><span>全部节点</span></div></div>
            <div class="metadata-divider" /><div class="trace-id-label">TRACE ID</div><div class="trace-id">{{ trace.id }}</div><div class="source-caption">来源 · {{ trace.source }}</div>
          </section>
          <section class="guide-card"><span class="guide-icon"><TraceIcon name="info" :size="17" /></span><h3>{{ trace.live ? '后端实际执行记录' : trace.demo ? '从一次调用开始探索' : '日志已在浏览器中解析' }}</h3><p>{{ trace.live ? '节点来自后端运行记录。点击节点，通过带身份校验的接口读取脱敏输入输出；此处展示已结束节点，可刷新获取晚到记录。' : trace.demo ? '演示日志仅用于交互预览，不作为模型实验结果。' : '按真实父子关系还原调用图。文件在浏览器本地解析，不上传。' }}</p><button v-if="trace.demo" type="button" @click="downloadExample">下载示例日志<TraceIcon name="arrow" :size="14" /></button></section>
        </aside>

        <section class="graph-column studio-panel">
          <div class="view-tabs"><button type="button" :class="{ active: mode === 'graph' }" :aria-pressed="mode === 'graph'" @click="mode = 'graph'"><TraceIcon name="trace" :size="14" />调用图</button><button type="button" :class="{ active: mode === 'list' }" :aria-pressed="mode === 'list'" @click="mode = 'list'"><TraceIcon name="list" :size="14" />节点列表</button><span class="view-tabs-note">{{ trace.live ? 'BACKEND TRACE' : trace.demo ? 'DEMO TRACE' : 'IMPORTED TRACE' }}</span></div>
          <TraceGraph :trace="trace" :selected-id="selectedId" :mode="mode" @select="selectNode" />
          <section class="timing-panel">
            <div class="timing-heading"><h2><TraceIcon name="clock" :size="15" />执行时间线<span>Timing</span></h2><span>相对开始时间 · ms</span></div>
            <div class="timing-axis"><span>0</span><span>{{ Math.round(trace.extentMs / 2) }}</span><span>{{ Math.round(trace.extentMs) }}</span></div>
            <div class="timing-rows"><button v-for="node in timingNodes" :key="node.id" type="button" class="timing-row" :class="{ active: node.id === selectedId, failed: ['FAILED','TIMED_OUT'].includes(node.status) }" :aria-label="`查看 ${node.label}，耗时 ${formatDuration(node.durationMs)}`" :aria-pressed="node.id === selectedId" @click="selectNode(node.id)"><span class="timing-name">{{ node.label }}<small v-if="node.turn != null">#{{ node.turn }}</small></span><span class="timing-track"><i :style="timingStyle(node)" :class="`bar-${node.kind.toLowerCase()}`" /></span><span class="timing-value">{{ formatDuration(node.durationMs) }}</span></button></div>
          </section>
        </section>

        <aside class="inspector-column studio-panel" aria-label="节点详情" aria-live="polite">
          <div class="inspector-heading"><h2>节点详情<span>Node Inspector</span></h2><span class="inspector-dot" /></div>
          <div v-if="selected" class="inspector-content" :key="selected.id">
            <div class="selected-node-heading"><span class="selected-node-icon" :class="selected.kind.toLowerCase()"><TraceIcon :name="iconFor(selected)" :size="21" /></span><div><span class="section-eyebrow">{{ kindLabel(selected) }}</span><h3 data-testid="inspector-node-name">{{ selected.label }}</h3></div></div>
            <div class="node-status-line"><span class="status-pill" :class="selected.status.toLowerCase()"><i />{{ statusLabel(selected.status) }}</span><span v-if="selected.turn != null">第 {{ selected.turn }} 轮</span><span v-if="selected.model">{{ selected.model }}</span></div>
            <div class="duration-card"><div><span>执行耗时</span><strong data-testid="inspector-duration">{{ formatDuration(selected.durationMs) }}</strong></div><div><span>占总耗时</span><b>{{ share }}</b></div></div>
            <div class="inspector-tabs"><button type="button" :class="{ active: detailTab === 'payload' }" :aria-pressed="detailTab === 'payload'" @click="detailTab = 'payload'">输入 / 输出</button><button type="button" :class="{ active: detailTab === 'metadata' }" :aria-pressed="detailTab === 'metadata'" @click="detailTab = 'metadata'">元信息</button></div>
            <p v-if="detailLoading" class="connection-note" role="status">正在从后端读取节点详情…</p><p v-if="detailError" class="connection-error" role="alert">{{ detailError }}<button type="button" @click="loadSelectedDetails">重试详情</button></p>
            <template v-if="detailTab === 'payload'">
              <TraceCode title="输入 Input" :value="selected.input" :state="selected.inputState" :reference="selected.inputRef" />
              <TraceCode title="输出 Output" :value="selected.output" :state="selected.outputState" :reference="selected.outputRef" />
              <section class="token-section"><div class="token-heading"><TraceIcon name="token" :size="14" /><h3>Token 用量</h3></div><div class="token-grid"><div><span>Input</span><b>{{ formatValue(selected.inputTokens) }}</b></div><div><span>Output</span><b>{{ formatValue(selected.outputTokens) }}</b></div><div><span>Total</span><b>{{ formatValue(selected.totalTokens) }}</b></div></div></section>
            </template>
            <template v-else><dl class="node-metadata"><div><dt>Span ID</dt><dd>{{ selected.id }}</dd></div><div><dt>Parent Span ID</dt><dd>{{ selected.parentId || '根节点' }}</dd></div><div><dt>开始时间</dt><dd>{{ clock(selected.startMs) }}</dd></div><div><dt>相对开始</dt><dd>{{ formatDuration(selected.offsetMs) }}</dd></div><div><dt>原始名称</dt><dd>{{ selected.name }}</dd></div></dl><TraceCode title="原始属性 Attributes" :value="selected.attributes" state="COMPLETE" /></template>
            <div class="inspector-footer"><span>SPAN ID</span><code>{{ selected.id }}</code></div>
          </div>
        </aside>
      </div>
      </template>
      <section v-else class="trace-empty studio-panel"><TraceIcon name="trace" :size="30" /><h2>{{ backendLoading ? '正在读取运行记录' : '等待连接运行数据' }}</h2><p>从上方加载已有运行，或执行一次提问。后端未连接时不会显示模拟结果。</p><p>默认接口地址由 TIANMU_AGENT_PROXY_TARGET 配置，当前开发默认指向本机 8201 端口。</p></section>
      <footer class="studio-footer"><span><i />Tianmu Trace Studio<span class="footer-divider">/</span>场景 C · 多模型行为分析</span><span>{{ sourceMode === 'backend' ? '数据来源：Agent 后端运行记录' : trace?.demo ? '演示数据，不代表模型评测结果' : '数据来源：本地导入日志' }}<span class="footer-divider">·</span>Trace Studio v0.2</span></footer>
    </main>
  </div>
</template>

<style>
html.trace-workspace-page,html.trace-workspace-page body { min-width:0;background:#0b1018; }
html.trace-workspace-page #app { max-width:none;background:#0b1018; }
</style>

<style scoped>
.comparison-nav-link{display:flex;align-items:center;gap:7px;text-decoration:none;color:#8fb5e9;font-size:11px;white-space:nowrap}.comparison-nav-link:hover{color:#7ed8d2}.trace-comparison-controls{padding:16px 20px;margin-bottom:18px}.comparison-controls-title{display:flex;align-items:center;gap:15px;margin-bottom:13px}.comparison-controls-title h2{display:flex;align-items:center;gap:8px;font-size:11px;font-weight:450;color:#9ebee7;margin:0}.comparison-controls-title span{color:#4e7198;font-size:9px}.comparison-controls-fields{display:flex;gap:12px;align-items:flex-end}.comparison-controls-fields label{display:flex;flex-direction:column;gap:6px;font-size:9px;color:#6888ad;flex:1;min-width:0}.comparison-controls-fields input,.comparison-controls-fields select,.comparison-basis-label input{width:100%;min-width:0;background:#0d1929;border:1px solid #2e4667;border-radius:5px;padding:8px 9px;font:10px inherit;color:#abc6e8}.comparison-basis-label{display:flex;align-items:center;gap:10px;margin-top:12px;font-size:9px;color:#6585ab}.comparison-basis-label input{flex:1}.comparison-controls-fields input::placeholder{color:#4a6c94}@media(max-width:700px){.trace-comparison-controls{padding:15px}.comparison-controls-fields{flex-wrap:wrap}.comparison-controls-fields label{flex:auto;width:calc(50% - 10px)}.comparison-controls-fields button{margin-left:auto}.comparison-controls-title{flex-wrap:wrap;gap:4px}.comparison-controls-title span{font-size:8px}.comparison-nav-link{font-size:9px}.comparison-nav-link svg{width:13px}}
.backend-connection { padding:18px 20px;margin-bottom:20px; }
.connection-title { display:flex;align-items:center;gap:12px;margin-bottom:16px; }
.connection-title h2 { margin:0;display:flex;align-items:center;gap:8px;color:#bcd0ec;font-size:13px;font-weight:500; }
.connection-title>span { color:#526f94;font:10px var(--trace-mono); }
.connection-title>button { margin-left:auto;background:none;border:0;color:#7d9fc9;font-size:10px;cursor:pointer; }
.connection-controls { display:flex;align-items:flex-end;gap:12px; }
.connection-controls label { display:flex;flex-direction:column;gap:6px;font-size:10px;color:#6985a8; }
.connection-controls select,.connection-controls input,.connection-create input { background:#0d1623;border:1px solid #2d405b;border-radius:5px;color:#b2c9e9;padding:9px 10px;min-width:0;font-size:11px;outline:none; }
.connection-controls select:focus,.connection-controls input:focus,.connection-create input:focus { border-color:#6dabae; }
.run-id-field { flex:1; }.run-id-field input { width:100%;font-family:var(--trace-mono); }
.connection-create { display:flex;gap:12px;margin-top:12px; }.connection-create input { flex:1; }
.connection-note { color:#728bab;font-size:10px;line-height:1.8;margin:12px 0 0;overflow-wrap:anywhere; }.connection-note strong { margin-left:15px;color:#a2bddf;font-weight:400; }
.connection-warning { color:#b29c70;font-size:10px;line-height:1.8;margin:8px 0 0; }
.connection-error { color:#e3a1a8;font-size:11px;line-height:1.8;margin:12px 0 0; }.connection-error button { color:#8cbce8;border:0;background:none;cursor:pointer;margin-left:8px;font-size:10px; }
.trace-empty { min-height:310px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:30px;color:#5e83ae;text-align:center; }.trace-empty h2 { color:#a9c2e1;font-size:17px;font-weight:400;margin:0; }.trace-empty p { color:#6480a3;font-size:11px;margin:0;line-height:1.8; }
@media(max-width:700px){.backend-connection{padding:16px}.connection-title>span{display:none}.connection-controls{flex-wrap:wrap;gap:10px}.connection-controls label:first-child{width:100%}.connection-controls label:first-child select{width:100%}.connection-controls .run-id-field{width:100%;flex:auto}.connection-controls>.import-button{margin-left:auto}.connection-create{flex-wrap:wrap}.connection-create input{width:100%;flex:auto}.connection-create button{margin-left:auto}.connection-note strong{display:block;margin-left:0}.trace-empty{padding:25px 15px}.trace-empty p{font-size:10px}}
.trace-studio { --trace-mono:'Cascadia Code','SFMono-Regular',Consolas,'Liberation Mono',monospace;min-height:100vh;background:radial-gradient(ellipse at 40% 0%,#14223944,transparent 55%),#0b1018;color:#cbd5e5;font-family:Inter,'Harmony Font','Microsoft YaHei',sans-serif;font-size:13px;line-height:1.5;color-scheme:dark; }
.trace-studio * { box-sizing:border-box; }.trace-studio button,.trace-studio select { font-family:inherit; }.trace-studio button:focus-visible,.trace-studio select:focus-visible,.trace-studio a:focus-visible { outline:2px solid #61d1cf;outline-offset:4px; }.trace-studio button:disabled { opacity:.5;cursor:wait; }
.studio-topbar { height:71px;border-bottom:1px solid #202a3b;display:flex;align-items:center;padding:0 34px;gap:18px;background:#101620b3; }.studio-brand { display:flex;align-items:center;gap:10px;color:#e5edf8;text-decoration:none; }.brand-symbol { display:grid;place-items:center;width:35px;height:35px;border-radius:10px;background:linear-gradient(145deg,#284369,#153241);color:#72d8dd;border:1px solid #355775; }.studio-brand strong { display:flex;align-items:baseline;gap:8px;font-weight:600;font-size:18px;letter-spacing:2px; }.studio-brand strong span { font:9px var(--trace-mono);letter-spacing:1.4px;color:#788da9; }.topbar-separator { height:20px;width:1px;background:#2b3545;margin:0 2px; }.studio-product { font-size:14px;font-weight:500;letter-spacing:.2px;color:#c0cde1; }.version-tag { padding:2px 5px;border:1px solid #344155;border-radius:4px;color:#8295b1;font:8px var(--trace-mono);letter-spacing:1px;margin-left:-10px; }.topbar-right { display:flex;align-items:center;gap:24px;margin-left:auto; }.local-status { display:flex;align-items:center;gap:7px;color:#7f91ab;font-size:11px; }.local-status i { width:5px;height:5px;border-radius:50%;background:#62bfa5;box-shadow:0 0 7px #62bfa522; }.avatar { width:29px;height:29px;border:1px solid #34415a;border-radius:50%;display:grid;place-items:center;background:#202f46;color:#9dbdeb;font:12px var(--trace-mono); }
.studio-main { max-width:2048px;margin:0 auto;padding:0 32px; }.workspace-heading { display:flex;align-items:center;justify-content:space-between;gap:15px;padding:28px 0 25px; }.workspace-eyebrow { font:9px var(--trace-mono);letter-spacing:1.8px;color:#6383ac; }.workspace-eyebrow span { margin:0 10px;color:#35485e; }h1 { margin:10px 0 0;font-size:23px;font-weight:550;letter-spacing:1px;color:#edf2fa;line-height:1.4; }h1>span { font-size:11px;letter-spacing:.2px;font-weight:400;color:#7586a0;margin-left:20px; }.heading-actions { display:flex;align-items:center;gap:15px; }.source-badge { display:flex;align-items:center;gap:6px;color:#c8a46b;font-size:10px;background:#342a1b55;border:1px solid #56452a77;padding:4px 8px;border-radius:5px; }.source-badge i { width:4px;height:4px;border-radius:50%;background:currentColor; }.source-badge.imported{color:#72cabb;background:#12342c55;border-color:#2d564b;}.import-button { display:flex;align-items:center;gap:8px;background:#1b2c43;border:1px solid #375275;border-radius:6px;padding:9px 14px;font-size:11px;color:#bfd4f3;cursor:pointer;transition:background .15s; }.import-button:hover { background:#253e5c; }.file-input { display:none; }.import-error { padding:12px 16px;margin-bottom:16px;display:flex;align-items:center;gap:10px;color:#f0a3a3;background:#3b1f27;border:1px solid #733644;border-radius:8px;font-size:12px; }
.trace-toolbar { display:flex;align-items:center;justify-content:space-between;border:1px solid #263145;border-radius:9px;background:#121b28;padding:13px 18px;margin-bottom:18px;gap:16px; }.trace-breadcrumb { display:flex;align-items:center;gap:10px;min-width:0;color:#6e85a4;font-size:11px; }.trace-breadcrumb strong { font:11px var(--trace-mono);font-weight:400;color:#b7c8e0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.status-pill { display:inline-flex;align-items:center;gap:5px;font-size:10px;font-weight:400;padding:3px 7px;border-radius:4px;color:#8a9bb4;background:#243044;white-space:nowrap; }.status-pill>i { width:4px;height:4px;display:block;border-radius:50%;background:currentColor; }.status-pill.succeeded{color:#79cdb0;background:#17362c}.status-pill.failed,.status-pill.timed_out { color:#ec969e;background:#3d232d; }.status-pill.running{color:#84b4f7;background:#203652}.toolbar-metrics { display:flex;align-items:center;gap:28px;flex-shrink:0; }.toolbar-metrics>span { display:flex;align-items:center;gap:8px;color:#7288a6; }.toolbar-metrics em { font-style:normal;font-size:10px; }.toolbar-metrics b { font:12px var(--trace-mono);font-weight:400;color:#d1def1; }.toolbar-metrics small { font-size:9px;color:#bfa374; }
.studio-layout { display:grid;grid-template-columns:228px minmax(0,1fr) 326px;gap:18px;align-items:start; }.studio-panel { border:1px solid #263145;border-radius:11px;background:#111925;overflow:hidden; }.metadata-card { padding:23px 20px 21px; }.section-eyebrow { color:#6e84a4;font:9px/1.5 var(--trace-mono);letter-spacing:1.4px; }.metadata-card>h2 { margin:5px 0 22px;font-size:15px;font-weight:500;color:#d4e0f0; }.trace-select-label { color:#667e9e;font-size:10px;display:block;margin-bottom:7px; }.trace-select-wrap { position:relative; }.trace-select-wrap select { width:100%;appearance:none;background:#172231;border:1px solid #2e3e55;border-radius:6px;color:#adbed5;padding:9px 26px 9px 10px;font-size:10px;cursor:pointer; }.trace-select-wrap>svg { position:absolute;right:9px;top:10px;color:#7188a7;pointer-events:none; }.metadata-list { margin:23px 0 0; }.metadata-list>div { display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:17px;font-size:11px; }.metadata-list dt { color:#7286a3;flex-shrink:0; }.metadata-list dd { margin:0;color:#bdcbe0;font:11px var(--trace-mono);text-align:right;overflow-wrap:anywhere;display:flex;align-items:center;justify-content:flex-end;gap:5px; }.metadata-list dd.model-label { font-size:10px;color:#9fbcef; }.metadata-list .text-succeeded { color:#7bc4ae; }.metadata-list .text-failed { color:#e49aa2; }.small-status { display:inline-block;width:4px;height:4px;background:currentColor;border-radius:50%; }
.run-range { display:flex;align-items:center;margin-top:28px;gap:5px; }.run-range>span { height:2px;background:#42628c;flex:1; }.run-range>span:first-child,.run-range>span:last-child { opacity:.35; }.run-range i { width:5px;height:5px;border-radius:50%;background:#81afea; }.range-labels { margin-top:9px;display:flex;justify-content:space-between;font:9px var(--trace-mono);color:#8296b3; }.run-date { display:flex;justify-content:space-between;color:#5d718f;font:9px var(--trace-mono);margin-top:15px; }.run-date span { color:#405773; }.metadata-divider { height:1px;background:#253044;margin:23px 0; }.call-totals { display:flex;justify-content:space-between;gap:10px; }.call-totals>div { display:flex;flex-direction:column;gap:7px; }.call-totals strong { color:#bccce2;font:20px var(--trace-mono);font-weight:400; }.call-totals span { font-size:9px;color:#7086a3; }.trace-id-label { color:#5c7494;font:8px var(--trace-mono);letter-spacing:1.4px; }.trace-id { color:#8b9cb6;font:9px/1.9 var(--trace-mono);overflow-wrap:anywhere;margin-top:8px; }.source-caption { margin-top:13px;font-size:9px;color:#516a89; }
.guide-card { padding:24px 10px 14px 6px; }.guide-icon { color:#587caa; }.guide-card h3 { color:#8ca5c7;font-size:11px;font-weight:400;margin:10px 0 7px; }.guide-card p { color:#566e8f;font-size:10px;line-height:1.9;margin:0; }.guide-card button { display:flex;align-items:center;gap:8px;border:0;background:none;padding:13px 0;color:#739bd0;font-size:10px;cursor:pointer; }.guide-card button:hover { color:#6cd7d3; }
.graph-column { display:flex;flex-direction:column;min-height:725px; }.view-tabs { display:flex;align-items:center;padding:0 21px;gap:24px;height:49px;border-bottom:1px solid #253044;flex-shrink:0; }.view-tabs button { display:flex;align-items:center;gap:7px;height:100%;position:relative;border:0;background:none;color:#667d9d;padding:0;font-size:11px;cursor:pointer; }.view-tabs button.active { color:#9bbcf0; }.view-tabs button.active:after { content:'';position:absolute;bottom:-1px;width:100%;height:2px;background:#77a7ed; }.view-tabs-note { margin-left:auto;color:#3e587b;font:8px var(--trace-mono);letter-spacing:1.5px; }
.timing-panel { border-top:1px solid #28354a;background:#121c2a;padding:20px 22px 19px; }.timing-heading { display:flex;justify-content:space-between;gap:12px;align-items:center; }.timing-heading h2 { display:flex;align-items:center;gap:8px;margin:0;color:#c2d1e5;font-size:12px;font-weight:500; }.timing-heading h2 svg { color:#708bad; }.timing-heading h2 span { color:#587397;font-size:10px;font-weight:400;margin-left:3px; }.timing-heading>span { font-size:9px;color:#5e789a; }.timing-axis { margin:17px 68px 9px 146px;display:flex;justify-content:space-between;color:#4e6b8e;font:8px var(--trace-mono); }.timing-rows { max-height:230px;overflow-y:auto;scrollbar-width:thin;scrollbar-color:#31445e transparent; }.timing-row { display:grid;grid-template-columns:135px minmax(0,1fr) 64px;align-items:center;width:100%;gap:10px;background:none;border:0;border-radius:4px;text-align:left;padding:7px 5px;color:#7e97b9;font:9px var(--trace-mono);cursor:pointer; }.timing-row:hover { background:#192a3e; }.timing-row.active { background:#1d324b;color:#a7ceff; }.timing-name { overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.timing-name small { margin-left:7px;font-size:8px;color:#4f6e95; }.timing-row.active .timing-name small { color:#7fa7db; }.timing-track { height:5px;background:repeating-linear-gradient(90deg,#253a5344 0,#253a5344 1px,transparent 1px,transparent 25%);position:relative; }.timing-track i { position:absolute;top:0;height:5px;border-radius:2px;background:#576f91;min-width:3px; }.timing-track i.bar-model { background:#5c88c6; }.timing-track i.bar-tool { background:#53a79f; }.timing-row.active .timing-track i { background:#76b3fb;box-shadow:0 0 10px #649ee433; }.timing-row.failed .timing-track i { background:#c57686; }.timing-value { color:#7892b4;text-align:right;font-size:9px; }
.inspector-column { min-height:725px; }.inspector-heading { display:flex;align-items:center;justify-content:space-between;padding:17px 21px;border-bottom:1px solid #253044;height:50px; }.inspector-heading h2 { font-size:12px;font-weight:500;margin:0;color:#c8d6e9; }.inspector-heading h2 span { font-size:10px;color:#617b9e;font-weight:400;margin-left:9px; }.inspector-dot { width:5px;height:5px;background:#5cd0c7;box-shadow:0 0 7px #5cd0c72a;border-radius:50%; }.inspector-content { padding:23px 20px 0; }.selected-node-heading { display:flex;align-items:center;gap:12px; }.selected-node-icon { width:40px;height:40px;border:1px solid #354b6d;background:#1b2b43;border-radius:9px;display:grid;place-items:center;color:#8cb7f6;flex-shrink:0; }.selected-node-icon.tool { background:#183635;border-color:#305855;color:#72c8bc; }.selected-node-heading h3 { margin:5px 0 0;font:14px var(--trace-mono);font-weight:400;color:#c6d8f2;overflow-wrap:anywhere; }.selected-node-heading .section-eyebrow { font-family:inherit;font-size:9px;letter-spacing:.4px;color:#5f7d9f; }.node-status-line { display:flex;align-items:center;gap:9px;margin-top:15px;font-size:9px;color:#6b85a8;flex-wrap:wrap; }.node-status-line .status-pill { font-size:9px; }.duration-card { margin:20px 0 23px;background:linear-gradient(110deg,#1d2c43,#152232);border:1px solid #2b405b;border-radius:8px;padding:15px;display:flex;justify-content:space-between;gap:12px;align-items:center; }.duration-card>div { display:flex;flex-direction:column;gap:7px; }.duration-card span { color:#6f89ab;font-size:9px; }.duration-card strong { color:#d2e3fa;font:23px var(--trace-mono);font-weight:400;letter-spacing:-.6px; }.duration-card>div:last-child { text-align:right; }.duration-card b { color:#83a8d9;font:12px var(--trace-mono);font-weight:400; }.inspector-tabs { display:flex;gap:24px;border-bottom:1px solid #26364d; }.inspector-tabs button { background:none;border:0;border-bottom:2px solid transparent;margin-bottom:-1px;padding:0 0 10px;font-size:10px;color:#5e7a9e;cursor:pointer; }.inspector-tabs button.active { color:#a4c3eb;border-color:#6b9fdd; }.token-section { margin-top:24px;padding-top:19px;border-top:1px solid #253044; }.token-heading { display:flex;align-items:center;gap:7px;color:#6e89ad; }.token-heading h3 { color:#9eb4d3;font-size:10px;font-weight:400;margin:0; }.token-grid { display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:14px; }.token-grid>div { display:flex;flex-direction:column;gap:7px; }.token-grid span { font:9px var(--trace-mono);color:#537095; }.token-grid b { font:14px var(--trace-mono);font-weight:400;color:#91accf; }.token-grid>div:last-child b { color:#a8c5eb; }.inspector-footer { margin-top:27px;padding:16px 0;border-top:1px solid #253044;display:flex;align-items:center;justify-content:space-between;gap:8px; }.inspector-footer span { font:8px var(--trace-mono);color:#476386;letter-spacing:1px; }.inspector-footer code { font:9px var(--trace-mono);color:#56749a; }.node-metadata { margin-top:22px; }.node-metadata>div { margin-top:16px; }.node-metadata dt { font-size:10px;color:#5e7b9f; }.node-metadata dd { font:11px/1.7 var(--trace-mono);color:#9bb4d6;margin:5px 0 0;overflow-wrap:anywhere; }
.studio-footer { display:flex;align-items:center;justify-content:space-between;gap:16px;padding:20px 0 24px;font-size:9px;color:#3f5c7c; }.studio-footer>span { display:flex;align-items:center;gap:6px; }.studio-footer i { width:4px;height:4px;background:#4d7c88;border-radius:50%; }.footer-divider { color:#2d435e;margin:0 5px; }
@media(min-width:1700px){.studio-main{padding:0 42px}.studio-layout{grid-template-columns:260px minmax(0,1fr) 370px;gap:22px}.graph-column,.inspector-column{min-height:780px}.inspector-content{padding:25px 24px 0}.metadata-card{padding:25px}.trace-toolbar{margin-bottom:22px}.workspace-heading{padding:33px 0 29px}}
@media(max-width:1350px){.studio-main{padding:0 22px}.studio-layout{grid-template-columns:202px minmax(0,1fr) 290px;gap:13px}.metadata-card{padding:20px 15px}.inspector-content{padding:20px 15px 0}.timing-panel{padding:18px 16px}.timing-row{grid-template-columns:111px minmax(0,1fr) 57px;gap:6px;font-size:8px}.timing-axis{margin-left:122px;margin-right:62px}.duration-card{padding:13px}.duration-card strong{font-size:21px}.toolbar-metrics{gap:18px}.inspector-heading{padding:17px 15px}.inspector-heading h2 span{font-size:9px}}
@media(max-width:1100px){.studio-layout{grid-template-columns:185px minmax(0,1fr);gap:14px}.inspector-column{grid-column:1/-1;min-height:0}.inspector-content{display:grid;grid-template-columns:1fr 1fr;gap:0 24px;padding:20px}.selected-node-heading,.node-status-line,.duration-card,.inspector-tabs,.token-section,.inspector-footer,.node-metadata{grid-column:1/-1}.inspector-content :deep(.trace-code){min-width:0}.metadata-card{padding:18px 13px}.graph-column{min-height:700px}h1>span{font-size:10px}.toolbar-metrics em{display:none}.guide-card{padding-right:0}}
@media(max-width:700px){.studio-topbar{padding:0 18px;height:61px;gap:12px}.studio-brand strong span,.version-tag,.local-status,.topbar-separator{display:none}.studio-product{font-size:11px}.topbar-right{gap:0}.studio-main{padding:0 14px}.workspace-heading{align-items:flex-start;padding:22px 0}h1{font-size:21px}h1>span{display:block;margin:8px 0 0;font-size:10px;letter-spacing:0}.workspace-eyebrow{font-size:7px;letter-spacing:1px}.heading-actions{gap:7px;flex-direction:column;align-items:flex-end}.source-badge{font-size:8px;padding:2px 5px}.import-button{font-size:10px;padding:7px 9px}.trace-toolbar{padding:11px 12px;align-items:flex-start;flex-direction:column;gap:10px;margin-bottom:13px}.trace-breadcrumb{gap:7px;width:100%}.trace-breadcrumb strong{font-size:9px}.toolbar-metrics{gap:24px}.toolbar-metrics em{display:block;font-size:9px}.toolbar-metrics b{font-size:10px}.studio-layout{display:flex;flex-direction:column;gap:14px}.metadata-column,.graph-column,.inspector-column{width:100%}.metadata-card{padding:16px}.metadata-card>h2{margin:5px 0 15px;font-size:13px}.metadata-list{display:grid;grid-template-columns:1fr 1fr;gap:12px 20px;margin-top:17px}.metadata-list>div{margin:0;font-size:10px}.metadata-list>div:first-child{grid-column:1/-1}.metadata-list dd{font-size:10px}.metadata-list>div:nth-child(2){display:none}.run-range,.range-labels,.run-date,.metadata-divider,.call-totals,.trace-id-label,.trace-id,.source-caption,.guide-card{display:none}.trace-select-label{font-size:9px}.graph-column{min-height:680px}.view-tabs{padding:0 16px;height:44px}.view-tabs-note{font-size:7px}.timing-heading h2{font-size:10px}.timing-heading>span{font-size:8px}.timing-heading h2 span{display:none}.timing-panel{padding:16px 12px}.inspector-content{display:block;padding:20px}.inspector-tabs{margin-bottom:0}.studio-footer{flex-direction:column;align-items:flex-start;gap:7px;font-size:8px;padding:17px 0}.duration-card{margin-bottom:23px}.metadata-card .section-eyebrow{font-size:8px}.node-status-line{margin-top:15px}.token-section{margin-top:22px}.inspector-content :deep(.trace-code){margin-top:22px}}
@media(prefers-reduced-motion:reduce){.trace-studio *{transition:none!important;scroll-behavior:auto!important}}
</style>
