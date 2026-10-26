<template>
  <section class="room-audience" aria-label="视频房间分析">
    <header><strong>视频房间 · 弹幕复盘</strong><button type="button" @click="open = !open">{{ open ? '收起' : '展开' }}</button></header>
    <div v-if="open">
      <form class="report-range" @submit.prevent="report">
        <p>分析报告：未填写时间时默认过去七天。以下时间均为北京时间。</p>
        <label>开始<input v-model="range.start" type="datetime-local" /></label>
        <label>结束<input v-model="range.end" type="datetime-local" /></label>
        <button :disabled="busy" type="submit">结合视频生成报告</button>
      </form>
      <div class="room-live-heading"><strong>实时弹幕汇总</strong><button type="button" @click="monitoring ? stop() : start()">{{ monitoring ? '停止汇总' : '开始汇总' }}</button></div>
      <p class="room-hint">使用当前视频的弹幕作为房间讨论；从开始汇总时累计已入库弹幕，页面每15秒刷新，话题最多每30秒更新。关闭面板停止刷新。</p>
      <p class="room-hint">话题最多分析120组高频同文，每组读取前80字；数量统计按全量记录计算，归类和建议需要复核。</p>
      <p v-if="error" role="status">{{ error }}</p>
      <template v-if="snapshot">
        <p v-if="snapshot.demo" class="demo-label">模拟数据，仅用于演示</p>
        <p class="room-hint">{{ snapshot.state === 'UPDATING' ? '正在更新，以下为上次结果。' : snapshot.state === 'ERROR' ? '更新失败，保留上次结果。' : '已更新' }} {{ formatDataUpdatedAt(snapshot.updatedAt) }}</p>
        <template v-if="snapshot.data">
          <div class="room-metrics"><span>弹幕 <b>{{ snapshot.data.totalMessages }}</b></span><span>参与用户 <b>{{ snapshot.data.uniqueSenders }}</b></span></div>
          <p class="room-hint">统计范围：{{ snapshot.start }} — {{ snapshot.end }}。不含尚未入库或已删除记录。</p>
          <button type="button" :disabled="busy" @click="reportSnapshot">按本次区间生成报告</button>
          <p class="room-hint">数量数据截至 {{ formatDataUpdatedAt(snapshot.data.dataAsOf) }}；话题摘要更新于 {{ formatDataUpdatedAt(snapshot.updatedAt) || '尚未生成' }}。</p>
          <p v-if="snapshot.data.audience" class="room-hint">当前话题语料覆盖 {{ snapshot.data.audience.corpusMessages }} / {{ snapshot.data.totalMessages }} 条；上次成功摘要归类 {{ classified }} 条。{{ snapshot.videoAnalyzed ? '已结合视频采样画面。' : '尚未结合视频画面。' }}</p>
          <article v-for="topic in snapshot.topics" :key="topic.id" class="room-topic">
            <h4>{{ topic.title }} <small>{{ topic.messages }} 条</small></h4><p>{{ topic.observation }}</p>
            <p v-if="topic.videoContext"><strong>相关画面：</strong>{{ topic.videoContext }}</p>
            <p v-if="topic.videoContext"><router-link v-for="at in topic.videoAtMs" :key="at" :to="`/video/${videoId}?t=${at / 1000}`">查看 {{ at / 1000 }} 秒采样位置 ↗ </router-link></p>
            <p v-if="topic.recommendation"><strong>建议：</strong>{{ topic.recommendation }}</p>
            <p v-if="topicTrend(topic)" class="room-hint">发送时间趋势（区间内12箱）：{{ topicTrend(topic) }}，仅含本话题已归类同文组。</p>
            <blockquote v-for="sample in examples(topic)" :key="sample.id">{{ sample.text }} <small>同文 {{ sample.count }} 条</small><router-link v-if="sample.playbackMs != null" :to="`/video/${videoId}?t=${sample.playbackMs / 1000}`">查看片段 ↗</router-link></blockquote>
          </article>
          <p v-if="!snapshot.topics.length">{{ snapshot.data.totalMessages ? '尚未形成可靠话题，数量统计仍可查看。' : '这段时间还没有已入库弹幕。' }}</p>
          <details v-if="snapshot.data.audience"><summary>最铁互动用户</summary><p class="room-hint">按活跃天数、不同原文数、发言数排序；不表示关注关系或忠诚度。</p><ol><li v-for="fan in snapshot.data.audience.interactions" :key="fan.userId">用户 {{ fan.userId }} · {{ fan.messages }} 条 · {{ fan.activeDays }} 天 · {{ fan.distinctTexts }} 种原文</li></ol></details>
        </template>
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { getRoomSummary } from '@/api/agent'
import { useUserStore } from '@/store/user'
import { createRoomMonitor } from '@/agent/room-monitor.mjs'
import { buildReportMessage, chinaTime, sevenDayRange, formatDataUpdatedAt } from '@/agent/protocol.mjs'
const props = defineProps({ videoId: { type: String, required: true }, busy: Boolean })
const emit = defineEmits(['report'])
const userStore = useUserStore()
const open = ref(false), monitoring = ref(false), snapshot = ref(null), error = ref('')
const range = reactive(sevenDayRange())
let defaultRange = { ...range }
let since = '', watchedVideo = ''
const classified = computed(() => (snapshot.value?.topics || []).reduce((n, t) => n + t.messages, 0))
function examples(topic) { if (snapshot.value?.state !== 'READY') return []; const ids = new Set(topic.sampleIds.slice(0, 3)); return (snapshot.value?.data?.audience?.corpus || []).filter(g => ids.has(g.id)) }
function topicTrend(topic) {
  if (snapshot.value?.state !== 'READY') return ''
  const groups = (snapshot.value?.data?.audience?.corpus || []).filter(g => topic.sampleIds.includes(g.id))
  if (!groups.length || groups.some(g => !g.trend || g.trend.length !== 12)) return ''
  return groups.reduce((bins, g) => bins.map((n, i) => n + g.trend[i]), Array(12).fill(0)).join(' → ')
}
function reportSnapshot() {
  try { emit('report', buildReportMessage({ videoId: props.videoId, start: snapshot.value.start, end: snapshot.value.end, focus: '结合视频内容整理话题、真实例子和建议。' })); error.value = '' }
  catch (e) { error.value = e.message }
}
function report() {
  try {
    let times = { ...range }
    if ((!times.start && !times.end) || (times.start === defaultRange.start && times.end === defaultRange.end)) {
      times = sevenDayRange(); defaultRange = { ...times }; Object.assign(range, times)
    }
    emit('report', buildReportMessage({ videoId: props.videoId, ...times, focus: '归纳主要话题，结合相关视频内容解释，附真实弹幕例子和改进建议，并展示互动用户榜。' }))
    error.value = ''
  } catch (e) { error.value = e.message }
}
const monitor = createRoomMonitor({
  fetchSnapshot: signal => getRoomSummary(watchedVideo, since, signal),
  onSnapshot(next) {
    snapshot.value = next
    error.value = next.errorCode === 'MODEL_NOT_CONFIGURED' ? '尚未配置话题模型，先展示数量统计。' : next.errorCode ? '部分分析暂不可用，保留可用数据，稍后更新。' : ''
  },
  onError(e) {
    error.value = e.message
    if ([401, 403].includes(e.status)) { stop(); snapshot.value = null }
  },
})
function stop() { monitor.stop(); monitoring.value = false }
function start() { stop(); watchedVideo = props.videoId; since = chinaTime(new Date(Date.now() - 1000)); snapshot.value = null; error.value = ''; monitoring.value = true; monitor.start() }
watch(() => props.videoId, () => { stop(); snapshot.value = null; error.value = ''; defaultRange = sevenDayRange(); Object.assign(range, defaultRange) })
watch(() => userStore.user.token, () => { stop(); snapshot.value = null; error.value = ''; open.value = false })
watch(open, value => { if (!value) stop() })
onBeforeUnmount(stop)
</script>

<style scoped>
.room-audience{margin:12px 0;padding:14px 16px;border:1px solid #dbe5ec;border-radius:12px;background:#f8fbfc;color:#263f4c;font-size:13px}.room-audience header,.room-live-heading{display:flex;align-items:center;justify-content:space-between;gap:10px}.room-audience button{border:1px solid #bed0d9;border-radius:7px;background:white;color:#20576b;padding:6px 10px;cursor:pointer}.room-audience button:disabled{opacity:.5}.report-range{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:18px}.report-range>p{width:100%}.report-range label{display:grid;gap:4px;flex:1;min-width:160px}.report-range input{padding:7px;border:1px solid #cad8e0;border-radius:6px;max-width:100%;box-sizing:border-box}.room-hint{color:#687d87;font-size:12px;line-height:1.6}.room-metrics{display:flex;gap:25px}.room-metrics b{font-size:21px}.room-topic{padding:12px;margin:10px 0;background:white;border-radius:9px;border:1px solid #e3ebef}.room-topic h4{margin:0 0 8px}.room-topic small{font-weight:normal;color:#6d8089}.room-topic blockquote{margin:8px 0;padding:8px 12px;border-left:3px solid #b8d7e0;background:#f5f9fb}.room-topic a{display:inline-block;margin-left:8px;color:#176b87}.demo-label{color:#93651c}
</style>
