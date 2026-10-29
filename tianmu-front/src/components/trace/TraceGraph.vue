<script setup>
/* global defineProps, defineEmits */
import { computed, nextTick, shallowRef, watch } from 'vue'
import { VueFlow, Handle, Position } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { layoutTrace, formatDuration, statusLabel } from '@/trace/trace-data.mjs'
import TraceIcon from './TraceIcon.vue'

const props = defineProps({ trace: { type: Object, required: true }, selectedId: String, mode: String })
const emit = defineEmits(['select'])
const instance = shallowRef(null)
const nodes = computed(() => layoutTrace(props.trace.nodes).map((node) => ({ ...node, selected: node.id === props.selectedId })))
const edges = computed(() => {
  const ids = new Set(props.trace.nodes.map((node) => node.id))
  const ancestors = new Set()
  let cursor = props.trace.nodes.find((node) => node.id === props.selectedId)
  while (cursor && ids.has(cursor.parentId)) {
    ancestors.add(`${cursor.parentId}-${cursor.id}`)
    cursor = props.trace.nodes.find((node) => node.id === cursor.parentId)
  }
  return props.trace.nodes.filter((node) => ids.has(node.parentId)).map((node) => ({
    id: `${node.parentId}-${node.id}`, source: node.parentId, target: node.id,
    type: 'default', style: { stroke: ancestors.has(`${node.parentId}-${node.id}`) ? '#5b9cf6' : '#35465e', strokeWidth: ancestors.has(`${node.parentId}-${node.id}`) ? 2.3 : 1.5 },
  }))
})
const nodeIcon = (node) => node.kind === 'MODEL' ? 'model' : node.kind === 'TOOL' ? 'tool' : 'stage'
const fit = () => instance.value?.fitView({ padding: 0.1, maxZoom: 1.05, duration: 250 })
function ready(flow) { instance.value = flow; fit() }
watch(() => props.trace.id, async () => { await nextTick(); fit() })
watch(() => props.mode, async () => { await nextTick(); fit() })
</script>

<template>
  <section class="trace-graph-panel">
    <div class="graph-titlebar">
      <div class="panel-title"><TraceIcon name="trace" :size="16" /><h2>调用图 <span>Trace Graph</span></h2><span class="node-count">{{ trace.nodes.length }} 节点</span></div>
      <div class="graph-legend"><span><i class="model-dot" />模型</span><span><i class="tool-dot" />工具</span><span><i class="stage-dot" />阶段</span></div>
    </div>
    <div v-if="mode === 'graph'" class="graph-canvas">
      <VueFlow
        :key="trace.id" :nodes="nodes" :edges="edges" :min-zoom="0.15" :max-zoom="1.8"
        :nodes-draggable="false" :nodes-connectable="false" :edges-updatable="false" :elements-selectable="true"
        :delete-key-code="null" :zoom-on-double-click="false" :fit-view-on-init="true" :pan-on-scroll="false"
        @pane-ready="ready" @node-click="({ node }) => emit('select', node.id)"
      >
        <template #node-trace="{ data, selected }">
          <div class="trace-node" :title="data.label" :class="[{ 'is-selected': selected, 'is-failed': ['FAILED','TIMED_OUT'].includes(data.status) }, `kind-${data.kind.toLowerCase()}`]" :data-testid="`trace-node-${data.id}`">
            <Handle type="target" :position="Position.Left" :connectable="false" />
            <div class="node-top"><span class="node-icon"><TraceIcon :name="nodeIcon(data)" :size="16" /></span><span class="node-name">{{ data.label }}</span><i class="node-status" :class="data.status.toLowerCase()" /></div>
            <div class="node-bottom"><span>{{ data.tool || data.model || data.name }}</span><b>{{ formatDuration(data.durationMs) }}</b></div>
            <Handle type="source" :position="Position.Right" :connectable="false" />
          </div>
        </template>
      </VueFlow>
      <div class="graph-caption"><i />连线表示父子调用关系</div>
      <div class="graph-controls">
        <button type="button" aria-label="缩小调用图" title="缩小" @click="instance?.zoomOut({ duration: 200 })"><TraceIcon name="minus" :size="16" /></button>
        <button type="button" aria-label="放大调用图" title="放大" @click="instance?.zoomIn({ duration: 200 })"><TraceIcon name="plus" :size="16" /></button>
        <span />
        <button type="button" aria-label="适应画布" title="适应画布" @click="fit"><TraceIcon name="fit" :size="16" /></button>
      </div>
    </div>
    <div v-else class="node-table-wrap">
      <table class="node-table">
        <thead><tr><th>节点 / 类型</th><th>耗时</th><th>状态</th></tr></thead>
        <tbody><tr v-for="node in trace.nodes" :key="node.id" :class="{ active: node.id === selectedId }"><td><button type="button" @click="emit('select', node.id)"><TraceIcon :name="nodeIcon(node)" :size="15" /><span>{{ node.label }}<small>{{ node.tool || node.model || node.name }}</small></span></button></td><td>{{ formatDuration(node.durationMs) }}</td><td :class="`status-${node.status.toLowerCase()}`">{{ statusLabel(node.status) }}</td></tr></tbody>
      </table>
    </div>
    <div class="graph-bottomline"><span>点击节点查看输入、输出与耗时</span><span>拖动画布平移 · 滚轮缩放</span></div>
  </section>
</template>

<style scoped>
.trace-graph-panel { display:flex;flex-direction:column;min-height:410px;flex:1;min-width:0; }
.graph-titlebar { padding:21px 22px 18px;display:flex;align-items:center;justify-content:space-between;gap:10px; }
.panel-title { display:flex;align-items:center;gap:9px;color:#91a7c4; }.panel-title h2 { margin:0;font-size:13px;font-weight:550;color:#d8e2f0;white-space:nowrap; }.panel-title h2 span { color:#687c98;font-size:11px;font-weight:400;margin-left:7px; }
.node-count { margin-left:4px;font-size:10px;color:#788ba7;border:1px solid #293448;border-radius:4px;padding:3px 6px;white-space:nowrap; }
.graph-legend { display:flex;gap:14px;font-size:10px;color:#8492a9; }.graph-legend span { display:flex;align-items:center;gap:5px; }.graph-legend i { width:5px;height:5px;border-radius:50%; }.model-dot{background:#729fff}.tool-dot{background:#5fc9be}.stage-dot{background:#8999b1}
.graph-canvas { position:relative;flex:1;min-height:390px;background-image:radial-gradient(#31405670 .65px,transparent .65px);background-size:18px 18px; }
.graph-canvas :deep(.vue-flow) { position:absolute;inset:0; }
.trace-node { width:191px;box-sizing:border-box;background:#151f2e;border:1px solid #35445c;border-radius:9px;padding:12px 13px 10px;color:#b6c8e2;transition:border-color .18s,box-shadow .18s;cursor:pointer; }
.node-top { display:flex;align-items:center;gap:8px; }.node-icon { display:flex;color:#829ab9; }.node-name { font:12px var(--trace-mono);white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }.node-status { width:5px;height:5px;flex-shrink:0;margin-left:auto;border-radius:50%;background:#6f8199; }.node-status.succeeded { background:#65bca5; }.node-status.failed,.node-status.timed_out { background:#f08e8b; }
.node-bottom { display:flex;justify-content:space-between;gap:8px;margin-top:9px;align-items:center;font:9px var(--trace-mono);color:#7386a1; }.node-bottom span { overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.node-bottom b { color:#a7b8ce;font-weight:400;white-space:nowrap; }
.kind-model { background:#162238;border-color:#344e76; }.kind-model .node-icon { color:#7aa7f8; }.kind-tool { background:#112a2c;border-color:#305052; }.kind-tool .node-icon { color:#6acfc3; }.trace-node.is-failed { background:#2a1c27;border-color:#704452; }.trace-node.is-selected { border-color:#68d8d4;box-shadow:0 0 0 3px #5ccece1c,0 0 24px #41cdd014;background:#162d3e; }.trace-node:hover { border-color:#6d91bd; }.trace-node.is-selected .node-name { color:#bfe4f7; }
.trace-node :deep(.vue-flow__handle) { width:5px;height:5px;min-width:5px;min-height:5px;background:#506b90;border:2px solid #111925; }.trace-node.is-selected :deep(.vue-flow__handle) { background:#6ed6d2; }
.graph-canvas :deep(.vue-flow__node:focus-visible) { outline:2px solid #63d4d1;outline-offset:6px;border-radius:10px; }
.graph-caption { position:absolute;bottom:15px;left:22px;color:#62748d;font-size:10px;display:flex;align-items:center;gap:6px;pointer-events:none; }.graph-caption i { width:12px;height:1px;background:#526b8e; }
.graph-controls { position:absolute;bottom:12px;right:19px;display:flex;align-items:center;padding:3px;background:#182130;border:1px solid #2c384c;border-radius:7px; }.graph-controls button { display:flex;padding:6px;border:0;background:none;color:#93a5bf;cursor:pointer; }.graph-controls button:hover { color:#72d9d4;background:#213043; }.graph-controls>span { height:15px;width:1px;background:#334054;margin:0 3px; }
.graph-bottomline { display:flex;justify-content:space-between;gap:10px;padding:13px 22px;border-top:1px solid #1e293a;color:#647690;font-size:10px; }
.node-table-wrap { overflow:auto;flex:1;max-height:540px;padding:0 20px; }.node-table { border-collapse:collapse;width:100%;font-size:11px; }.node-table th { text-align:left;padding:12px 8px;font-weight:400;color:#6c809b; }.node-table td { border-top:1px solid #253044;padding:10px 8px;color:#b0bfd3; }.node-table tr.active { background:#193445; }.node-table button { display:flex;align-items:center;gap:10px;border:0;background:none;padding:4px 0;color:#aac5eb;text-align:left;cursor:pointer;font:12px var(--trace-mono); }.node-table small { display:block;color:#6e839f;font-size:10px;margin-top:5px; }.node-table .status-failed,.node-table .status-timed_out { color:#ee9397; }
@media(max-width:1350px){.panel-title h2 span{display:none}.graph-titlebar{padding:18px 16px}.graph-legend{gap:9px}.graph-bottomline{padding:12px 16px;font-size:9px}}
@media(max-width:700px){.graph-legend{display:none}.graph-bottomline span:last-child{display:none}}
</style>
