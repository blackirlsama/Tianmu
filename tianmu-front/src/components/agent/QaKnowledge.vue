<template>
  <details class="qa-knowledge" :open="open" @toggle="toggle">
    <summary>常见问答 <span>重复问题达到阈值后自动加入</span></summary>
    <div v-if="open" class="qa-body">
      <p v-if="policy">至少 {{ policy.minimumSessions }} 个独立会话提供相同答案，且答案一致率达到 {{ Math.round(policy.minimumAnswerShare * 100) }}% 后，自动用于你的后续问答。只积累独立会话首轮的稳定知识回答，不收录业务统计和报告结论。</p>
      <p v-if="policy && !policy.enabled">自动加入与直接回答目前已暂停，已有条目仍可管理。</p>
      <p v-if="error" role="status">{{ error }}</p>
      <button type="button" :disabled="busy" @click="load">{{ busy ? '正在读取…' : '刷新' }}</button>
      <p v-if="!busy && !items.length">还没有达到积累条件的问答。</p>
      <article v-for="item in items" :key="item.id">
        <div><strong>{{ item.question }}</strong><small>{{ labels[item.status] || item.status }}</small></div>
        <p class="qa-answer">{{ item.answer || '等待新的独立会话积累答案。' }}</p>
        <p>{{ item.support }} 个会话答案相同 / 共 {{ item.sessions }} 个会话 · 一致率 {{ Math.round(item.answerShare * 100) }}%</p>
        <button v-if="item.status !== 'DISABLED'" type="button" :disabled="busy" @click="change(item, false)">停用</button>
        <button type="button" :disabled="busy" @click="change(item, true)">清空积累，重新学习</button>
      </article>
    </div>
  </details>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { useUserStore } from '@/store/user'
import { getQaCatalog, changeQaEntry } from '@/api/agent'
const user = useUserStore()
const open = ref(false), busy = ref(false), error = ref(''), items = ref([]), policy = ref(null)
const labels = { CANDIDATE: '积累中', ACTIVE: '已加入', DISABLED: '已停用', EXPIRED: '已过期', PAUSED: '已暂停' }
let epoch = 0, controller
function stop() { epoch++; controller?.abort(); controller = null; busy.value = false }
async function load() {
  stop(); const current = epoch; controller = new AbortController(); busy.value = true; error.value = ''
  try { const result = await getQaCatalog(controller.signal); if (current === epoch) { items.value = result.items; policy.value = result.policy } }
  catch (e) { if (current === epoch && e.name !== 'AbortError') error.value = e.message }
  finally { if (current === epoch) busy.value = false }
}
function toggle(event) { const next = event.target.open; if (next === open.value) return; open.value = next; if (next) load(); else stop() }
async function change(item, reset) {
  stop(); const current = epoch; controller = new AbortController(); busy.value = true; error.value = ''
  try { await changeQaEntry(item.id, item.version, reset, controller.signal); if (current === epoch) await load() }
  catch (e) { if (current === epoch && e.name !== 'AbortError') error.value = e.message }
  finally { if (current === epoch) busy.value = false }
}
watch(() => [user.user.token, user.user.userId], () => { stop(); open.value = false; items.value = []; policy.value = null; error.value = '' })
onBeforeUnmount(stop)
</script>

<style scoped>
.qa-knowledge{margin:12px 0;padding:14px 18px;border:1px solid #e1e8ec;border-radius:12px;background:#fff;color:#304751;font-size:13px}.qa-knowledge summary{cursor:pointer;font-weight:650}.qa-knowledge summary span{font-size:12px;font-weight:400;color:#73828c;margin-left:10px}.qa-body>p{line-height:1.7;color:#677b87}.qa-body article{border-top:1px solid #e8edef;padding:15px 0}.qa-body article>div{display:flex;justify-content:space-between;gap:12px}.qa-body small{color:#477b87;white-space:nowrap}.qa-body button{border:1px solid #d5e1e5;background:#f8fbfc;border-radius:6px;padding:5px 10px;cursor:pointer;margin-right:8px}.qa-body button:disabled{cursor:default;opacity:.6}.qa-answer{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7}
</style>
