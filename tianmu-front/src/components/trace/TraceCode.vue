<script setup>
/* global defineProps */
import { computed, ref } from 'vue'
import TraceIcon from './TraceIcon.vue'

const props = defineProps({ title: String, value: { default: null }, state: String, reference: String })
const copied = ref(false)
const copyError = ref(false)
const content = computed(() => typeof props.value === 'string' ? props.value : JSON.stringify(props.value, null, 2))
const tokens = computed(() => {
  if (props.value == null) return []
  return content.value.split(/("(?:\\.|[^"\\])*"\s*:|"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|\b\d+(?:\.\d+)?\b)/g).filter(Boolean).map((text) => ({
    text, type: /^".*:\s*$/.test(text) ? 'key' : /^"/.test(text) ? 'string' : /^(true|false|null|\d)/.test(text) ? 'literal' : 'plain',
  }))
})
async function copy() {
  try {
    await navigator.clipboard.writeText(content.value)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1800)
  } catch { copyError.value = true; setTimeout(() => { copyError.value = false }, 2500) }
}
</script>

<template>
  <section class="trace-code">
    <div class="code-heading">
      <h3>{{ title }}</h3>
      <div class="code-actions">
        <span v-if="value != null" class="code-format">{{ typeof value === 'string' ? 'TEXT' : 'JSON' }}</span>
        <span v-if="state === 'TRUNCATED'" class="code-state">已截断</span>
        <button v-if="value != null" type="button" :aria-label="`复制${title}`" :title="copyError ? '复制失败，请手动选择文本' : copied ? '已复制' : '复制内容'" @click="copy"><TraceIcon :name="copied ? 'check' : 'copy'" :size="14" /></button>
      </div>
    </div>
    <pre v-if="value != null" tabindex="0"><code><span v-for="(token, index) in tokens" :key="index" :class="`syntax-${token.type}`">{{ token.text }}</span></code></pre>
    <div v-else class="code-empty">
      <TraceIcon name="info" :size="17" />
      <p>{{ reference ? '该日志仅包含详情引用。请导入包含脱敏输入输出的 JSON，或后续连接详情接口。' : '此节点未提供该内容。' }}<small>采集状态：{{ state || 'NOT_CAPTURED' }}</small></p>
    </div>
  </section>
</template>

<style scoped>
.trace-code { margin-top: 25px; }
.code-heading,.code-actions { display:flex;align-items:center;justify-content:space-between;gap:10px; }
h3 { margin:0;font-size:12px;font-weight:550;color:#bdc8d9;letter-spacing:.03em; }
.code-format { font:10px var(--trace-mono);color:#64748d;letter-spacing:.1em; }
.code-state { font-size:10px;color:#e9bc72; }
button { border:0;background:none;color:#74839a;padding:3px;cursor:pointer;display:flex; }
button:hover { color:#62d5d0; }
pre { margin:11px 0 0;background:#0d121b;border:1px solid #202938;border-radius:8px;padding:15px 13px;white-space:pre-wrap;overflow-wrap:anywhere;overflow:auto;max-height:235px;font:11px/1.85 var(--trace-mono);tab-size:2;color:#8fa0b8; }
.syntax-key { color:#8aade1; }.syntax-string { color:#b8cc97; }.syntax-literal { color:#e1ba7a; }
.code-empty { display:flex;gap:9px;margin-top:10px;padding:14px 12px;background:#0d121b;border:1px dashed #253044;border-radius:8px;color:#6e7f98; }
.code-empty svg { flex-shrink:0;margin-top:2px; }.code-empty p { margin:0;font-size:11px;line-height:1.8; }.code-empty small { display:block;font:10px/1.8 var(--trace-mono);margin-top:6px; }
</style>
