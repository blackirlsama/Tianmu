<template>
  <section class="creator-draft" aria-label="作者互动草稿">
    <header><strong>{{ kindLabel }}预览</strong><span>{{ stateLabel }}</span></header>
    <p class="draft-hint">仅以你本人的作者账号发送。先修改内容和勾选条目，再确认发送。</p>
    <p v-if="loading" role="status">正在读取草稿…</p>
    <template v-if="draft">
      <div class="draft-meta">视频 #{{ draft.videoId }} · 作者账号 #{{ draft.userId }}</div>
      <label v-for="(item, index) in items" :key="item.id" class="draft-item">
        <span><input v-model="item.selected" type="checkbox" :disabled="locked" /> 第 {{ index + 1 }} 条</span>
        <textarea v-model="item.text" :maxlength="draft.kind === 'COMMENT' ? 500 : 80" :disabled="locked" :aria-label="`第${index + 1}条${kindLabel}`" rows="2" />
        <span v-if="draft.kind === 'DANMAKU'">播放时间（秒）<input v-model.number="item.seconds" type="number" min="0" step="0.001" :disabled="locked" :aria-label="`第${index + 1}条弹幕的播放时间`" /></span>
      </label>
      <div v-if="draft.state !== 'PUBLISHED'" class="draft-actions">
        <button type="button" :disabled="publishing || !selected.length" @click="publish">{{ publishing ? '正在发送…' : draft.state === 'PUBLISHING' ? '重试确认发送结果' : `确认发送 ${selected.length} 条${kindLabel}` }}</button>
      </div>
      <p v-if="draft.state === 'PUBLISHING'" class="draft-hint">发送结果尚未确认。内容已锁定，请使用上面的重试按钮，避免重新生成后重复发送。</p>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="!draft && !loading" type="button" @click="load">重新读取草稿</button>
  </section>
</template>

<script setup>
/* global defineProps */
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { getCreatorDraft, confirmCreatorDraft } from '@/api/agent'
import { useUserStore } from '@/store/user'
const props = defineProps({ runId: { type: String, required: true } })
const user = useUserStore()
const draft = ref(null), items = ref([]), loading = ref(false), publishing = ref(false), error = ref('')
let controller, epoch = 0
const kindLabel = computed(() => draft.value?.kind === 'DANMAKU' ? '弹幕' : '评论')
const stateLabel = computed(() => ({ DRAFT: '尚未发送', PUBLISHING: '待确认发送结果', PUBLISHED: '已发送' })[draft.value?.state] || '')
const locked = computed(() => publishing.value || draft.value?.state !== 'DRAFT')
const selected = computed(() => items.value.filter(item => item.selected).map(item => ({ id: item.id, text: item.text, playbackMs: draft.value?.kind === 'DANMAKU' ? Math.round(Number(item.seconds) * 1000) : null })))
function stop() { epoch++; controller?.abort(); controller = null }
function apply(next) {
  draft.value = next
  items.value = (next?.items || []).map(item => ({ ...item, seconds: item.playbackMs == null ? null : item.playbackMs / 1000, selected: true }))
}
async function load() {
  stop(); const current = epoch; controller = new AbortController(); loading.value = true; error.value = ''
  try { const next = await getCreatorDraft(props.runId, controller.signal); if (current === epoch) apply(next) }
  catch (e) { if (current === epoch && e.name !== 'AbortError') error.value = e.message }
  finally { if (current === epoch) loading.value = false }
}
async function publish() {
  if (publishing.value || !selected.value.length) return
  const payload = selected.value
  stop(); const current = epoch; controller = new AbortController(); publishing.value = true; error.value = ''
  try { const next = await confirmCreatorDraft(props.runId, payload, controller.signal); if (current === epoch) { apply(next) } }
  catch (e) {
    if (current !== epoch || e.name === 'AbortError') return
    error.value = e.message
    // A timeout may occur after publication. Read the frozen server payload before enabling any further action.
    try { const next = await getCreatorDraft(props.runId, controller.signal); if (current === epoch) { apply(next) } }
    catch { if (current === epoch && draft.value) apply({ ...draft.value, state: 'PUBLISHING', items: payload }) }
  } finally { if (current === epoch) publishing.value = false }
}
watch(() => [props.runId, user.user.token], () => { apply(null); publishing.value = false; load() }, { immediate: true })
onBeforeUnmount(stop)
</script>

<style scoped>
.creator-draft{margin-top:18px;padding:18px;border:1px solid #e6d9e8;border-radius:14px;background:#fdfaff;color:#453b4a}.creator-draft header,.draft-actions{display:flex;justify-content:space-between;gap:10px;align-items:center}.creator-draft header span,.draft-hint,.draft-meta{font-size:12px;color:#88778c}.draft-item{display:grid;gap:9px;padding:14px 0;border-bottom:1px solid #eee6ef}.draft-item>span{font-size:12px}.draft-item textarea{box-sizing:border-box;width:100%;resize:vertical;padding:10px;border:1px solid #ddd1e1;border-radius:8px;font:inherit;line-height:1.6;background:white;color:inherit}.draft-item input[type=number]{width:100px;margin-left:8px;padding:5px}.draft-actions{justify-content:flex-start;margin-top:14px;flex-wrap:wrap}.creator-draft button{padding:9px 14px;background:white;border:1px solid #cca7c5;border-radius:8px;color:#83577c;cursor:pointer}.creator-draft button:disabled{opacity:.5;cursor:default}.creator-draft [role=alert]{color:#ab4c47;font-size:13px}
</style>
