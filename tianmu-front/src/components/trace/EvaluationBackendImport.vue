<script setup>
/* global defineEmits */
import { onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/store/user'
import { readStoredUser } from '@/lib/auth'
import { createTraceClient } from '@/trace/trace-client.mjs'
import { loadEvaluationRuns } from '@/trace/evaluation-backend.mjs'
import TraceIcon from './TraceIcon.vue'
const emit = defineEmits(['loaded'])
const expanded = ref(false), ids = ref(''), condition = ref(''), busy = ref(false), progress = ref(''), error = ref(''), failures = ref([])
const user = useUserStore()
let controller, generation = 0
function cancel() { generation++; controller?.abort(); busy.value = false }
onBeforeUnmount(cancel)
watch(() => user.user?.token, () => { cancel(); failures.value = []; error.value = ''; progress.value = '' })
async function load() {
  cancel()
  const current = generation
  controller = new AbortController()
  busy.value = true; error.value = ''; failures.value = []; progress.value = '正在读取…'
  try {
    const client = createTraceClient({ getToken: () => readStoredUser().token })
    const result = await loadEvaluationRuns({ text: ids.value, conditionId: condition.value.trim(), client, signal: controller.signal,
      onProgress: ({ completed, total }) => { if (current === generation) progress.value = `${completed} / ${total} 次读取完成` } })
    if (current !== generation) return
    failures.value = result.errors
    if (result.dataset) emit('loaded', result.dataset)
    else error.value = '没有可加入的运行；请核对登录态、运行 ID 和日志保留期。'
  } catch (failure) {
    if (failure.name !== 'AbortError' && current === generation) error.value = failure.message
  } finally { if (current === generation) busy.value = false }
}
</script>

<template>
  <section class="evaluation-backend-import">
    <button class="backend-import-heading" type="button" :aria-expanded="expanded" @click="expanded = !expanded"><TraceIcon name="trace" :size="16" /><strong>读取后端已采集运行</strong><span>只读查询，不创建新任务或模型调用</span><TraceIcon name="down" :size="14" /></button>
    <div v-if="expanded" class="backend-import-body"><p>使用天幕网站当前登录态。将同一账号已完成的运行 ID 粘贴到下方，读取模型、任务、耗时、Token 和调用图；回答正确性仍需评分。不同模型需由对应 Agent 实际执行，填写模型名不能改变后端模型。</p><div class="backend-import-fields"><label>运行 ID（每行一个，最多 50 个）<textarea v-model="ids" rows="4" aria-label="批量后端运行 ID" placeholder="粘贴实际运行 UUID" /></label><div><label>统一实验条件编号<input v-model="condition" maxlength="160" aria-label="批量实验条件" placeholder="提示词、工具、输入、预算一致的实验组" /></label><button type="button" :disabled="busy || !ids.trim()" @click="load">{{ busy ? '正在读取…' : '读取并加入总览' }}<TraceIcon name="arrow" :size="14" /></button><button v-if="busy" type="button" @click="cancel">取消读取</button><span role="status">{{ progress }}</span></div></div><p v-if="error" class="backend-read-error" role="alert">{{ error }}</p><ul v-if="failures.length" class="backend-read-failures"><li v-for="failure in failures" :key="failure.runId">{{ failure.runId }}：{{ failure.message }}</li></ul></div>
  </section>
</template>

<style scoped>
.evaluation-backend-import{margin-bottom:18px;border:1px solid #2b405e;border-radius:8px;background:#111f31;overflow:hidden}.backend-import-heading{display:flex;align-items:center;gap:9px;width:100%;border:0;background:none;color:#8facd2;padding:15px 18px;cursor:pointer;text-align:left}.backend-import-heading strong{font-size:11px;font-weight:450}.backend-import-heading span{font-size:9px;color:#597da7}.backend-import-heading>svg:last-child{margin-left:auto}.backend-import-body{padding:0 18px 18px;border-top:1px solid #283c57}.backend-import-body p{color:#7391b6;font-size:10px;line-height:1.9;margin:13px 0}.backend-import-fields{display:grid;grid-template-columns:1.4fr 1fr;gap:18px}.backend-import-fields label{display:flex;flex-direction:column;gap:7px;color:#6386b0;font-size:10px}.backend-import-fields textarea,.backend-import-fields input{color:#afc9e9;background:#0c1828;border:1px solid #304d71;border-radius:5px;padding:10px;font:10px/1.7 Consolas,monospace;width:100%;box-sizing:border-box}.backend-import-fields textarea{resize:vertical;min-height:110px}.backend-import-fields>div>button{display:inline-flex;align-items:center;gap:8px;background:#254363;border:1px solid #466a94;padding:8px 12px;border-radius:5px;color:#b7d4f7;font-size:10px;cursor:pointer;margin:13px 8px 0 0}.backend-import-fields button:disabled{opacity:.45;cursor:default}.backend-import-fields>div>span{display:block;font-size:10px;color:#6488b2;margin-top:10px}.backend-import-body .backend-read-error{color:#e5a1ac}.backend-read-failures{font:9px/1.9 Consolas,monospace;color:#aa8c7d;padding-left:15px;overflow-wrap:anywhere}@media(max-width:700px){.backend-import-heading{padding:13px;flex-wrap:wrap}.backend-import-heading span{width:100%;font-size:8px}.backend-import-heading>svg:last-child{position:absolute;right:29px}.backend-import-fields{grid-template-columns:1fr;gap:12px}.backend-import-body{padding:0 13px 14px}.backend-import-body p{font-size:9px}}
</style>
