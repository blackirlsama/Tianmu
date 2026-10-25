<template>
  <div class="agent-workspace" :class="{ embedded }">
    <aside class="workspace-sidebar">
      <a class="workspace-brand" href="/">天幕<span>运营助手</span></a>
      <button class="new-analysis" :disabled="busy" @click="newAnalysis">＋ 开启新对话</button>
      <div class="history-heading">
        最近对话 <span>{{ history.length }}</span>
      </div>
      <p v-if="!history.length" class="history-empty">聊过的内容会留在这里，随时回来继续。</p>
      <nav aria-label="最近对话" class="history-list">
        <button
          v-for="item in history"
          :key="item.runId"
          :class="{ selected: activeRunId === item.runId }"
          :disabled="busy"
          @click="selectHistory(item)"
        >
          <span class="history-title">{{ item.title }}</span>
          <span class="history-meta"
            >{{ formatDate(item.createdAt) }} · {{ statusLabel(item.status) }}</span
          >
        </button>
      </nav>
      <div class="sidebar-footer"><span class="online-dot" /> 天幕 AI · 和你一起做好内容</div>
    </aside>
    <main class="workspace-main">
      <header class="workspace-header">
        <div class="assistant-identity">
          <span class="brand-symbol">✦</span>
          <div><strong>天幕运营助手</strong><span>内容洞察，从对话开始</span></div>
        </div>
        <button
          v-if="embedded"
          class="close-assistant"
          aria-label="关闭运营助手"
          @click="$emit('close')"
        >
          ×
        </button>
        <router-link v-else class="back-video" :to="form.videoId ? '/video/' + form.videoId : '/'"
          >返回{{ form.videoId ? '视频' : '网站' }} ↗</router-link
        >
      </header>
      <div ref="conversationElement" class="conversation-scroll">
        <div class="workspace-content">
          <section v-if="!turns.length" class="workspace-intro">
            <div class="welcome-symbol" aria-hidden="true">✦<i>✧</i></div>
            <div class="intro-tag">你的内容运营搭档</div>
            <h1>一起，读懂观众的声音。</h1>
            <p>聊聊视频表现、观众反馈，或下一次内容的灵感。<br />需要一份报告？直接告诉我就好。</p>
            <div class="conversation-context" v-if="form.videoId">
              <span>▣ 当前视频</span><strong>{{ contextTitle || '视频 ' + form.videoId }}</strong
              ><small>#{{ form.videoId }}</small>
            </div>
            <div class="suggestion-grid">
              <button
                v-for="item in suggestions"
                :key="item.text"
                @click="useSuggestion(item.text)"
              >
                <span class="suggestion-icon">{{ item.icon }}</span
                ><strong>{{ item.title }}</strong
                ><span>{{ item.description }}</span
                ><i>↗</i>
              </button>
            </div>
          </section>
          <div v-if="notice" class="workspace-notice" role="alert">
            {{ notice
            }}<button
              v-if="(activeTurn || validRunId(currentQueryRun)) && !submitting && !loadingRun"
              @click="resume"
            >
              重新连接
            </button>
          </div>

          <section
            v-if="turns.length"
            ref="resultsElement"
            class="results"
            aria-label="运营助手对话"
          >
            <article v-for="turn in turns" :key="turn.runId" class="analysis-turn">
              <div class="user-question">
                <span>你</span>
                <p>{{ turn.message }}</p>
              </div>
              <section class="assistant-result">
                <div class="result-heading">
                  <span class="assistant-avatar" aria-hidden="true">✦</span
                  ><strong>天幕运营助手</strong
                  ><span
                    class="status-badge"
                    :class="(turn.snapshot?.status || '').toLowerCase()"
                    >{{ statusLabel(turn.snapshot?.status) }}</span
                  >
                </div>
                <div v-if="turn.snapshot" class="execution-summary">
                  <span v-if="!isTerminal(turn.snapshot.status)"
                    >已等待 {{ elapsed(turn.snapshot.createdAt) }}</span
                  ><span v-if="turn.snapshot.totalModelCalls"
                    >模型调用 {{ turn.snapshot.totalModelCalls }} 次</span
                  ><span v-if="turn.snapshot.steps">已执行 {{ turn.snapshot.steps }} 轮</span>
                </div>
                <div
                  v-if="!isTerminal(turn.snapshot?.status)"
                  class="working-message"
                  role="status"
                >
                  <span class="working-dot" />{{
                    turn.events.length
                      ? turn.events[turn.events.length - 1].label
                      : '正在准备回复…'
                  }}
                </div>
                <p v-if="turn.snapshot?.status === 'NEEDS_INPUT'" class="answer-text clarification">
                  {{ turn.snapshot.clarification }}
                </p>
                <p v-if="turn.snapshot?.answer" class="answer-text">
                  {{ displayAnswer(answerPresentation(turn.snapshot.answer).text) }}
                </p>
                <details v-if="answerPresentation(turn.snapshot?.answer).notes.length" class="answer-data-notes">
                  <summary>数据说明</summary>
                  <div v-for="(note, index) in answerPresentation(turn.snapshot.answer).notes" :key="index">
                    <span v-if="note.label">{{ note.label }}</span>
                    <p>{{ note.text }}</p>
                    <time v-if="formatDataUpdatedAt(note.updatedAt)">更新于 {{ formatDataUpdatedAt(note.updatedAt) }}</time>
                  </div>
                </details>
                <p v-if="turn.snapshot?.status === 'FAILED'" class="failure-message">
                  {{ failureLabel(turn.snapshot.errorCode) }}
                </p>
                <p v-if="turn.snapshot?.status === 'CANCELLED'" class="muted">
                  {{
                    turn.snapshot.stopReason === 'SUPERSEDED'
                      ? '任务已被新的请求替代。'
                      : '这次回复已停止。'
                  }}
                </p>
                <button
                  v-if="!isTerminal(turn.snapshot?.status) && turn.runId === activeRunId"
                  class="text-button"
                  :disabled="cancelling"
                  @click="cancel"
                >
                  {{ cancelling ? '正在停止…' : '停止生成' }}
                </button>
                <details v-if="turn.events.length" class="execution-details">
                  <summary>查看执行进度 · {{ turn.events.length }} 条记录</summary>
                  <ol>
                    <li v-for="event in turn.events" :key="event.sequence">
                      <span>{{ event.label }}</span
                      ><time>{{ formatTime(event.at) }}</time>
                    </li>
                  </ol>
                </details>
                <div
                  v-if="turn.reportPath && turn.snapshot?.status === 'SUCCEEDED'"
                  class="report-panel"
                >
                  <div class="report-toolbar">
                    <div>
                      <strong>弹幕分析报告</strong><span>HTML 报告 · 数据概览与内容观察</span>
                    </div>
                    <button
                      v-if="turn.reportHtml"
                      class="text-button"
                      @click="downloadReport(turn)"
                    >
                      下载报告 ↓
                    </button>
                  </div>
                  <p v-if="turn.reportLoading" class="report-state" role="status">正在加载报告…</p>
                  <div v-else-if="turn.reportError" class="report-state" role="alert">
                    {{ turn.reportError }}
                    <button class="text-button" @click="loadReport(turn)">重新加载</button>
                  </div>
                  <iframe
                    v-else-if="turn.reportHtml"
                    :srcdoc="reportDocument(turn)"
                    sandbox=""
                    referrerpolicy="no-referrer"
                    title="弹幕分析报告预览"
                    class="report-preview"
                  />
                </div>
              </section>
            </article>
          </section>
        </div>
      </div>
      <div class="composer-wrap">
        <div v-if="turns.length && form.videoId" class="composer-context">
          <span>▣ {{ contextTitle || '当前视频' }}</span
          ><small>#{{ form.videoId }}</small
          ><span v-if="needsInput" class="reply-hint">助手需要你补充信息</span>
        </div>
        <form class="chat-composer" @submit.prevent="submit">
          <label class="sr-only" for="assistant-message">给运营助手发消息</label>
          <textarea
            id="assistant-message"
            ref="composerInput"
            v-model="focus"
            :disabled="busy"
            :maxlength="messageLimit"
            rows="3"
            :placeholder="
              needsInput
                ? '补充一下信息，我们接着聊…'
                : '问问运营助手，比如：昨天的弹幕表现怎么样？'
            "
            @keydown.enter.exact="composerEnter"
          />
          <div class="composer-actions">
            <span><i>✦</i> {{ running ? '正在为你处理…' : '可以追问，也可以换个话题' }}</span
            ><button
              type="submit"
              class="send-button"
              :disabled="busy || !focus.trim()"
              :aria-label="submitting ? '正在发送' : '发送消息'"
            >
              {{ submitting ? '…' : '↑' }}
            </button>
          </div>
        </form>
        <p class="workspace-footnote">
          Enter 发送 · Shift + Enter 换行 <span>AI 内容供参考，请结合实际判断</span>
        </p>
      </div>
    </main>
  </div>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useUserStore } from '@/store/user';
import {
  cancelAgentRun,
  createAgentRun,
  getAgentReport,
  getAgentRun,
  readAgentEvents,
} from '@/api/agent';
import {
  answerReport,
  answerPresentation,
  formatDataUpdatedAt,
  buildChatRequest,
  progressLabel,
  reportPath,
  terminal,
  validRunId,
  yesterdayRange,
} from '@/agent/protocol.mjs';

const props = defineProps({
  embedded: { type: Boolean, default: false },
  videoId: { type: String, default: '' },
  videoTitle: { type: String, default: '' },
});
const emit = defineEmits(['close']);
const composerInput = ref(null),
  conversationElement = ref(null);
const suggestions = [
  {
    icon: '◷',
    title: '看看弹幕表现',
    description: '先从昨天的互动数据聊起',
    text: '昨天有多少条弹幕？',
  },
  {
    icon: '✧',
    title: '一起想想运营思路',
    description: '让观众更愿意参与讨论',
    text: '怎样提高观众的互动意愿？请给我一些具体建议。',
  },
  {
    icon: '▤',
    title: '整理一份分析报告',
    description: '把数据和观察留成一份复盘',
    text: '请分析昨天的弹幕并生成一份分析报告。',
  },
  {
    icon: '↗',
    title: '优化下一场内容',
    description: '从内容节奏和互动设计开始',
    text: '直播开场可以怎样设计，才能吸引观众留下来？',
  },
];

const router = useRouter(),
  route = useRoute(),
  userStore = useUserStore();
const form = reactive({ videoId: '', ...yesterdayRange() });
const contextTitle = computed(() => (props.videoId === form.videoId ? props.videoTitle : ''));
const currentQueryRun = computed(() =>
  props.embedded ? route.query.assistantRunId : route.query.runId
);
const messageLimit = 2000;
const focus = ref(''),
  turns = ref([]),
  history = ref([]),
  notice = ref('');
const submitting = ref(false),
  cancelling = ref(false),
  loadingRun = ref(false),
  clock = ref(Date.now()),
  resultsElement = ref(null);
const activeTurn = computed(() => turns.value[turns.value.length - 1]);
const activeRunId = computed(() => activeTurn.value?.runId);
const running = computed(() => !!activeTurn.value && !terminal(activeTurn.value.snapshot?.status));
const busy = computed(() => submitting.value || running.value || loadingRun.value);
const needsInput = computed(() => activeTurn.value?.snapshot?.status === 'NEEDS_INPUT');
const ownerId = computed(() => String(userStore.user.userId || ''));
const historyKey = computed(() => `TIANMU_agent_runs_${ownerId.value}`);
let monitor,
  submitController,
  reportControllers = new Set(),
  pollTimer,
  pendingRequest,
  restoreTicket;
const clockTimer = setInterval(() => {
  clock.value = Date.now();
}, 1000);

function statusLabel(status) {
  return (
    {
      QUEUED: '排队中',
      RUNNING: '思考中',
      SUCCEEDED: '已完成',
      NEEDS_INPUT: '等你补充',
      FAILED: '回复未完成',
      CANCELLED: '已取消',
    }[status] || '连接中'
  );
}
function failureLabel(code) {
  return (
    {
      DATE_OUT_OF_RANGE: '分析时段超出范围，请选择最近 30 天内已经结束的时段。',
      FORBIDDEN: '你没有权限分析这项内容，请确认视频或直播间 ID。',
      DEADLINE_EXCEEDED: '这次回复耗时较长，请稍后重试。',
      MODEL_UNAVAILABLE: '模型服务暂时不可用，请稍后重试。',
      INVALID_ANSWER: '这次没能整理出合适的回复，请重新发送一下。',
      INTERNAL_ERROR: '助手暂时遇到了一点问题，请稍后重试。',
    }[code] || '暂时没能完成回复，请稍后重试。'
  );
}
const isTerminal = terminal;
function formatDate(value) {
  return value
    ? new Date(value).toLocaleDateString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        month: '2-digit',
        day: '2-digit',
      })
    : '';
}
function formatTime(value) {
  return value
    ? new Date(value).toLocaleTimeString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        hour12: false,
      })
    : '';
}
function elapsed(value) {
  const seconds = Math.max(0, Math.floor((clock.value - Date.parse(value)) / 1000));
  return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}
function displayAnswer(text) {
  return text.replace(
    /\[查看 HTML 报告\]\(\/api\/agent\/runs\/[^)]+\)（需登录鉴权）/g,
    '报告预览见下方。'
  );
}

function readHistory() {
  try {
    const data = JSON.parse(localStorage.getItem(historyKey.value));
    history.value = Array.isArray(data)
      ? data
          .filter((item) => item && validRunId(item.runId) && typeof item.title === 'string')
          .slice(0, 30)
      : [];
  } catch {
    history.value = [];
  }
}
function saveHistory(turn) {
  const item = {
    runId: turn.runId,
    title: (turns.value[0]?.message || turn.message).slice(0, 44),
    sessionId: turn.snapshot.sessionId,
    createdAt: turn.snapshot.createdAt,
    status: turn.snapshot.status,
    videoId: turn.videoId,
  };
  history.value = [
    item,
    ...history.value.filter(
      (entry) =>
        entry.runId !== item.runId && (!item.sessionId || entry.sessionId !== item.sessionId)
    ),
  ].slice(0, 30);
  try {
    localStorage.setItem(historyKey.value, JSON.stringify(history.value));
  } catch {
    /* History storage must not interrupt an analysis. */
  }
}
function stopMonitoring(cancelReports = true) {
  monitor?.abort();
  clearTimeout(pollTimer);
  if (cancelReports) {
    reportControllers.forEach((controller) => controller.abort());
    reportControllers.clear();
  }
}

async function loadReport(turn) {
  if (turn.reportLoading || turn.reportHtml) return;
  const controller = new AbortController();
  reportControllers.add(controller);
  turn.reportLoading = true;
  turn.reportError = '';
  try {
    turn.reportHtml = await getAgentReport(turn.runId, turn.reportPath, controller.signal);
  } catch (error) {
    if (error.name !== 'AbortError') turn.reportError = error.message;
  } finally {
    turn.reportLoading = false;
    reportControllers.delete(controller);
  }
}
function applySnapshot(turn, snapshot) {
  turn.snapshot = snapshot;
  turn.reportPath = answerReport(turn.runId, snapshot.answer?.text) || turn.reportPath;
  saveHistory(turn);
  if (snapshot.status === 'SUCCEEDED' && turn.reportPath) loadReport(turn);
}

function startMonitoring(turn) {
  stopMonitoring(false);
  const controller = new AbortController();
  monitor = controller;
  const signal = controller.signal;
  let sequence = 0;
  const refresh = async () => {
    const snapshot = await getAgentRun(turn.runId, signal);
    if (!signal.aborted) {
      notice.value = '';
      applySnapshot(turn, snapshot);
    }
  };
  const fatal = (error) => error.status === 401 || error.status === 403 || error.status === 404;
  const poll = async () => {
    try {
      await refresh();
      if (signal.aborted) return;
      if (terminal(turn.snapshot?.status)) {
        controller.abort();
        return;
      }
    } catch (error) {
      if (signal.aborted) return;
      notice.value = error.message;
      if (fatal(error)) {
        controller.abort();
        return;
      }
    }
    if (!signal.aborted) pollTimer = setTimeout(poll, 2500);
  };
  pollTimer = setTimeout(poll, 2500);
  const stream = async () => {
    while (!signal.aborted) {
      try {
        await readAgentEvents(turn.runId, sequence, signal, (event) => {
          if (signal.aborted) return;
          const payload = event.payload,
            seq = Number(payload.sequence || event.id);
          if (!Number.isSafeInteger(seq) || seq <= sequence) return;
          sequence = seq;
          const data = payload.data || {};
          if (event.type === 'PROGRESS') {
            turn.events.push({
              sequence: seq,
              label: progressLabel(data),
              at: payload.at,
            });
            turn.events = turn.events.slice(-60);
          }
          if (event.type === 'ARTIFACT_READY')
            turn.reportPath = reportPath(turn.runId, data.url) || turn.reportPath;
        });
        await refresh();
        if (signal.aborted) return;
        if (terminal(turn.snapshot?.status)) {
          clearTimeout(pollTimer);
          return;
        }
      } catch (error) {
        if (signal.aborted) return;
        notice.value = error.message;
        if (fatal(error)) {
          controller.abort();
          clearTimeout(pollTimer);
          return;
        }
        if (error.code === 'EVENT_CURSOR_EXPIRED' || error.code === 'INVALID_CURSOR') sequence = 0;
      }
      await new Promise((resolve) => {
        const timer = setTimeout(resolve, 1500);
        signal.addEventListener(
          'abort',
          () => {
            clearTimeout(timer);
            resolve();
          },
          { once: true }
        );
      });
    }
  };
  stream();
}

function createKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}
function useSuggestion(text) {
  focus.value = text;
  nextTick(() => composerInput.value?.focus());
}
function composerEnter(event) {
  if (event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  submit();
}
function replaceRun(id, videoId = form.videoId) {
  return props.embedded
    ? router.replace({ query: { ...route.query, assistant: '1', assistantRunId: id || undefined } })
    : router.replace({
        name: 'AgentWorkspace',
        query: { runId: id || undefined, videoId: videoId || undefined },
      });
}
async function submit() {
  if (busy.value) return;
  notice.value = '';
  let body;
  try {
    body = buildChatRequest(focus.value, form.videoId);
  } catch (error) {
    notice.value = error.message;
    return;
  }
  if (activeTurn.value?.snapshot?.sessionId) body.sessionId = activeTurn.value.snapshot.sessionId;
  if (needsInput.value) body.replyToRunId = activeRunId.value;
  const fingerprint = JSON.stringify(body);
  if (!pendingRequest || pendingRequest.fingerprint !== fingerprint)
    pendingRequest = { fingerprint, key: createKey() };
  submitting.value = true;
  submitController = new AbortController();
  try {
    const snapshot = await createAgentRun(body, pendingRequest.key, submitController.signal);
    pendingRequest = null;
    const turn = reactive({
      runId: snapshot.runId,
      videoId: form.videoId,
      message: focus.value.trim(),
      snapshot,
      events: [],
      reportPath: '',
      reportHtml: '',
      reportLoading: false,
      reportError: '',
    });
    turns.value.push(turn);
    focus.value = '';
    applySnapshot(turn, snapshot);
    startMonitoring(turn);
    await replaceRun(snapshot.runId);
    await nextTick();
    conversationElement.value?.scrollTo({
      top: conversationElement.value.scrollHeight,
      behavior: 'smooth',
    });
  } catch (error) {
    if (error.name !== 'AbortError') notice.value = error.message;
  } finally {
    submitting.value = false;
  }
}

async function restoreRun(id) {
  stopMonitoring();
  notice.value = '';
  loadingRun.value = true;
  const controller = new AbortController();
  monitor = controller;
  restoreTicket = controller;
  try {
    const snapshot = await getAgentRun(id, controller.signal);
    if (controller.signal.aborted) return;
    const saved = history.value.find((item) => item.runId === id);
    const turn = reactive({
      runId: id,
      videoId: '',
      message:
        snapshot.task?.originalQuery?.replace(/\n\n\[页面上下文：[\s\S]*\]$/, '') ||
        saved?.title ||
        '已恢复的对话',
      snapshot,
      events: [],
      reportPath: '',
      reportHtml: '',
      reportLoading: false,
      reportError: '',
    });
    const conditions = snapshot.task?.specification?.conditions;
    const originalId = snapshot.task?.originalQuery?.match(
      /(?:视频|直播间|videoId)\s*([1-9]\d{0,18})/i
    )?.[1];
    if (saved?.videoId) form.videoId = saved.videoId;
    else if (originalId) form.videoId = originalId;
    else if (!form.videoId && Number.isSafeInteger(conditions?.video_id))
      form.videoId = String(conditions.video_id);
    if (conditions?.start) form.start = conditions.start.slice(0, 16);
    if (conditions?.end) form.end = conditions.end.slice(0, 16);
    turn.videoId = form.videoId;
    turns.value = [turn];
    applySnapshot(turn, snapshot);
    startMonitoring(turn);
  } catch (error) {
    if (error.name !== 'AbortError') {
      notice.value = error.message;
      turns.value = [];
    }
  } finally {
    if (restoreTicket === controller) loadingRun.value = false;
  }
}
function selectHistory(item) {
  replaceRun(item.runId, item.videoId || '');
}
function newAnalysis() {
  stopMonitoring();
  turns.value = [];
  focus.value = '';
  notice.value = '';
  pendingRequest = null;
  form.videoId = props.videoId || form.videoId;
  replaceRun();
}
function resume() {
  notice.value = '';
  if (activeTurn.value) startMonitoring(activeTurn.value);
  else if (validRunId(currentQueryRun.value)) restoreRun(currentQueryRun.value);
}
async function cancel() {
  if (!activeTurn.value || cancelling.value) return;
  cancelling.value = true;
  const turn = activeTurn.value;
  try {
    applySnapshot(turn, await cancelAgentRun(turn.runId, monitor?.signal));
  } catch (error) {
    if (error.name !== 'AbortError') notice.value = error.message;
  } finally {
    cancelling.value = false;
  }
}
function reportDocument(turn) {
  return `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">${turn.reportHtml}`;
}
function downloadReport(turn) {
  const url = URL.createObjectURL(new Blob([turn.reportHtml], { type: 'text/html;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `弹幕分析-${turn.runId}.html`;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

readHistory();
watch(
  () => [currentQueryRun.value, props.videoId || route.query.videoId],
  ([id, videoId]) => {
    if (typeof videoId === 'string' && /^\d{1,19}$/.test(videoId)) form.videoId = videoId;
    if (validRunId(id) && id !== activeRunId.value) restoreRun(id);
    else if (id && !validRunId(id)) notice.value = '任务链接不正确，请从最近任务重新打开。';
    else if (!id && activeTurn.value) {
      stopMonitoring();
      turns.value = [];
      focus.value = '';
      notice.value = '';
    }
  },
  { immediate: true }
);
watch(
  () => userStore.user.token,
  () => {
    stopMonitoring();
    submitController?.abort();
    turns.value = [];
    if (props.embedded) emit('close');
    else router.replace('/');
  }
);
onBeforeUnmount(() => {
  stopMonitoring();
  submitController?.abort();
  clearInterval(clockTimer);
});
</script>

<style scoped>
.agent-workspace {
  --ink: #292b38;
  --muted: #8c8e9c;
  --line: #eeedf2;
  --accent: #cc5b81;
  display: flex;
  height: 100dvh;
  min-height: 600px;
  background: #fbfafc;
  color: var(--ink);
  font: 14px/1.6 'Harmony Font', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.agent-workspace * {
  box-sizing: border-box;
}
.agent-workspace button,
.agent-workspace input,
.agent-workspace textarea {
  font: inherit;
}
.agent-workspace button {
  cursor: pointer;
}
.agent-workspace button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.agent-workspace a {
  color: inherit;
  text-decoration: none;
}
.agent-workspace button:focus-visible,
.agent-workspace a:focus-visible {
  outline: 3px solid #e8b0c4;
  outline-offset: 3px;
}
.workspace-sidebar {
  width: 214px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 28px 18px 20px;
  background: #f4f2f6;
  border-right: 1px solid var(--line);
}
.workspace-brand {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 2px;
  padding: 0 10px;
}
.workspace-brand span {
  display: block;
  font-size: 11px;
  color: #9992a0;
  font-weight: 400;
  letter-spacing: 4px;
  margin-top: 2px;
}
.new-analysis {
  margin: 28px 0;
  background: #fff;
  border: 1px solid #e6dce5;
  border-radius: 11px;
  padding: 11px;
  color: #91647b;
  box-shadow: 0 3px 9px #33223d04;
}
.history-heading {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #9992a0;
  padding: 0 9px;
  margin-bottom: 12px;
}
.history-empty {
  color: var(--muted);
  font-size: 12px;
  padding: 0 9px;
  line-height: 1.9;
}
.history-list {
  overflow: auto;
  flex: 1;
}
.history-list button {
  display: block;
  text-align: left;
  width: 100%;
  border: 0;
  background: none;
  padding: 12px 10px;
  border-radius: 9px;
  margin-bottom: 5px;
}
.history-list button:hover,
.history-list button.selected {
  background: #fff;
}
.history-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #5e5869;
}
.history-meta {
  display: block;
  font-size: 10px;
  color: #aaa3af;
  margin-top: 5px;
}
.sidebar-footer {
  font-size: 10px;
  color: #aaa0ae;
  padding-top: 18px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.online-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #70ae97;
}
.workspace-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: radial-gradient(ellipse at 75% 0%, #fceff550, transparent 50%), #fdfcfe;
}
.workspace-header {
  height: 78px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  border-bottom: 1px solid #f1eef4;
  background: #ffffffad;
}
.assistant-identity {
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-symbol {
  display: grid;
  place-items: center;
  width: 37px;
  height: 37px;
  border-radius: 12px;
  color: #bd7298;
  background: linear-gradient(135deg, #fce3ed, #ebe9fb);
  font-size: 26px;
}
.assistant-identity strong {
  display: block;
  font-size: 14px;
  font-weight: 650;
}
.assistant-identity div > span {
  display: block;
  font-size: 10px;
  color: #aaa1b1;
  letter-spacing: 1px;
}
.close-assistant {
  border: 1px solid #ede8ef;
  background: #fff;
  border-radius: 50%;
  width: 32px;
  height: 32px;
  font-size: 23px !important;
  line-height: 1;
  color: #9b909e;
}
.back-video {
  font-size: 12px;
  color: #9b8b9a !important;
}
.conversation-scroll {
  flex: 1;
  overflow: auto;
  min-height: 0;
  overscroll-behavior: contain;
}
.workspace-content {
  max-width: 900px;
  margin: 0 auto;
  padding: 26px 36px;
}
.workspace-intro {
  text-align: center;
  padding: 32px 0 20px;
}
.welcome-symbol {
  position: relative;
  display: grid;
  place-items: center;
  width: 76px;
  height: 76px;
  border: 1px solid #f0dfeb;
  border-radius: 24px;
  margin: 0 auto 23px;
  background: linear-gradient(140deg, #fff0f5, #f0edfc);
  color: #c97fa7;
  font-size: 54px;
  box-shadow: 0 12px 30px #bf8fba10;
}
.welcome-symbol i {
  position: absolute;
  right: -9px;
  top: -10px;
  font-size: 25px;
  font-style: normal;
  color: #ad98d1;
}
.intro-tag {
  font-size: 11px;
  color: #bd8da7;
  letter-spacing: 3px;
}
.workspace-intro h1 {
  font-size: 29px;
  letter-spacing: 1px;
  font-weight: 650;
  margin: 10px 0 13px;
}
.workspace-intro p {
  font-size: 12px;
  line-height: 1.9;
  color: #9992a1;
  margin: 0 0 22px;
}
.conversation-context {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 7px 11px;
  border: 1px solid #ece8f0;
  border-radius: 8px;
  background: #fff;
  max-width: 100%;
  font-size: 10px;
}
.conversation-context > span {
  color: #ba94a9;
  white-space: nowrap;
}
.conversation-context strong {
  font-size: 11px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}
.conversation-context small {
  color: #b7b0bc;
}
.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 11px;
  margin-top: 30px;
  text-align: left;
}
.suggestion-grid button {
  position: relative;
  text-align: left;
  padding: 17px 18px 17px 49px;
  border: 1px solid #ece7f0;
  background: #fff;
  border-radius: 13px;
  transition: border-color 0.2s, transform 0.2s;
}
.suggestion-grid button:hover {
  border-color: #deb4cc;
  transform: translateY(-2px);
}
.suggestion-grid strong {
  display: block;
  font-size: 12px;
  font-weight: 550;
  margin-bottom: 4px;
}
.suggestion-grid button > span:not(.suggestion-icon) {
  font-size: 10px;
  color: #aaa1af;
}
.suggestion-icon {
  position: absolute;
  left: 17px;
  top: 18px;
  font-size: 19px;
  color: #bc99b5;
}
.suggestion-grid i {
  position: absolute;
  right: 13px;
  top: 12px;
  font-style: normal;
  color: #d4c6d6;
  font-size: 13px;
}
.composer-wrap {
  flex-shrink: 0;
  width: 100%;
  max-width: 900px;
  align-self: center;
  padding: 12px 36px 19px;
  background: linear-gradient(transparent, #fdfcfe 12%);
}
.chat-composer {
  border: 1px solid #e6dce9;
  border-radius: 17px;
  padding: 13px 17px 11px;
  background: #fff;
  box-shadow: 0 6px 25px #50325507;
  transition: border-color 0.2s;
}
.chat-composer:focus-within {
  border-color: #d4a2bd;
  box-shadow: 0 0 0 3px #f9edf3;
}
.chat-composer textarea {
  display: block;
  border: 0;
  outline: none !important;
  resize: none;
  background: none;
  width: 100%;
  min-height: 64px;
  max-height: 150px;
  font-size: 13px;
  line-height: 1.8;
  color: #484251;
  padding: 0;
}
.chat-composer textarea::placeholder {
  color: #b4aab9;
}
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 5px;
}
.composer-actions > span {
  font-size: 10px;
  color: #b4a7b9;
}
.composer-actions i {
  font-style: normal;
  font-size: 15px;
  color: #c29bb8;
  margin-right: 5px;
}
.send-button {
  height: 34px;
  width: 34px;
  border: 0;
  border-radius: 11px;
  color: white;
  background: #c8729b;
  font-size: 23px !important;
  line-height: 1;
}
.send-button:hover {
  background: #b55e88;
}
.workspace-footnote {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 9px;
  color: #b7afbe;
  margin: 9px 3px 0;
}
.composer-context {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 3px 9px;
  color: #9b889c;
  font-size: 10px;
}
.composer-context > span:first-child {
  max-width: 65%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.composer-context small {
  color: #b5a9bb;
}
.reply-hint {
  margin-left: auto;
  color: #ba8b59;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.workspace-notice {
  padding: 12px 15px;
  background: #fff7eb;
  color: #9c7a49;
  border: 1px solid #f1e4cf;
  border-radius: 10px;
  font-size: 12px;
  margin: 12px 0;
}
.workspace-notice button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  margin-left: 10px;
}
.analysis-turn {
  margin: 0 0 28px;
}
.user-question {
  display: flex;
  flex-direction: row-reverse;
  align-items: flex-start;
  gap: 10px;
  margin: 0 0 22px;
}
.user-question > span {
  background: #f0e7f1;
  color: #a18aa4;
  border-radius: 11px;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  font-size: 11px;
  flex-shrink: 0;
}
.user-question p {
  max-width: 85%;
  background: #f5eef5;
  border: 1px solid #eee4ef;
  border-radius: 14px 2px 14px 14px;
  padding: 11px 15px;
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
  line-height: 1.9;
}
.assistant-result {
  background: #fff;
  border: 1px solid #efebf2;
  border-radius: 14px;
  padding: 20px;
  min-width: 0;
}
.result-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.assistant-avatar {
  width: 27px;
  height: 27px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: #f7ecf4;
  color: #c08daf;
  font-size: 20px;
}
.status-badge {
  font-size: 9px;
  margin-left: auto;
  padding: 3px 7px;
  border-radius: 20px;
  background: #f5f3f7;
  color: #a29aa9;
}
.status-badge.succeeded {
  background: #eef7f1;
  color: #73a38b;
}
.status-badge.running,
.status-badge.queued {
  background: #f8edf4;
  color: #bf86a6;
}
.status-badge.failed {
  background: #fff0eb;
  color: #ae6652;
}
.status-badge.needs_input {
  background: #fff8e9;
  color: #b9965e;
}
.execution-summary {
  display: flex;
  gap: 14px;
  font-size: 10px;
  color: #b0a4b5;
  margin: 10px 0;
}
.working-message {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #bd86a6;
  font-size: 12px;
  padding: 18px 0;
}
.working-dot {
  height: 6px;
  width: 6px;
  border-radius: 50%;
  background: #c48fb1;
  animation: pulse 1.4s infinite;
}
.answer-text {
  font-size: 13px;
  line-height: 1.95;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 16px 0;
  color: #62596a;
}
.answer-data-notes { font-size: 11px; color: #a195a8; margin: 8px 0 14px; }
.answer-data-notes summary { cursor: pointer; width: fit-content; }
.answer-data-notes > div { margin-top: 10px; padding: 10px 12px; background: #faf8fb; border: 1px solid #f0eaf3; border-radius: 8px; line-height: 1.8; }
.answer-data-notes div > span { font-size: 10px; color: #b1a3b9; }
.answer-data-notes p { margin: 4px 0; }
.answer-data-notes time { font-size: 10px; color: #afa0b7; }
.clarification {
  padding: 12px;
  background: #fffbf1;
  border-radius: 8px;
}
.failure-message {
  color: #ad6958;
  font-size: 12px;
}
.muted {
  color: var(--muted);
}
.text-button {
  border: 0;
  background: none;
  color: #b77b9e;
  font-size: 11px !important;
  padding: 4px 0;
}
.execution-details {
  margin-top: 15px;
  font-size: 10px;
  color: #aaa0af;
  border-top: 1px solid #f3eff5;
  padding-top: 10px;
}
.execution-details summary {
  cursor: pointer;
}
.execution-details ol {
  margin: 12px 0 0;
  padding-left: 19px;
  max-height: 150px;
  overflow: auto;
}
.execution-details li {
  padding: 3px;
}
.execution-details time {
  float: right;
  font-size: 9px;
  color: #bbb0c0;
}
.report-panel {
  margin-top: 17px;
  border: 1px solid #eee7f0;
  border-radius: 10px;
  overflow: hidden;
}
.report-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px;
  background: #faf7fb;
  gap: 8px;
}
.report-toolbar strong {
  display: block;
  font-size: 12px;
}
.report-toolbar div > span {
  display: block;
  color: #aa9caf;
  font-size: 9px;
  margin-top: 3px;
}
.report-preview {
  width: 100%;
  height: 650px;
  border: 0;
  display: block;
  background: #fff;
}
.report-state {
  padding: 15px;
  color: #a38eaa;
  font-size: 12px;
}
.embedded {
  height: 100%;
  min-height: 0;
}
.embedded .workspace-sidebar {
  width: 190px;
  padding: 24px 13px;
}
.embedded .workspace-content {
  padding: 22px 30px;
}
.embedded .composer-wrap {
  padding: 12px 30px 17px;
}
.embedded .workspace-intro {
  padding-top: 22px;
}
.embedded .workspace-intro h1 {
  font-size: 26px;
}
@keyframes pulse {
  50% {
    opacity: 0.3;
  }
}
@media (max-width: 760px) {
  .workspace-sidebar {
    display: none !important;
  }
  .workspace-header {
    height: 66px;
    padding: 0 20px;
  }
  .workspace-content,
  .embedded .workspace-content {
    padding: 20px;
  }
  .composer-wrap,
  .embedded .composer-wrap {
    padding: 10px 20px 14px;
  }
  .workspace-intro h1,
  .embedded .workspace-intro h1 {
    font-size: 23px;
  }
  .suggestion-grid {
    gap: 9px;
  }
  .suggestion-grid button {
    padding: 15px 12px 15px 38px;
  }
  .suggestion-icon {
    left: 11px;
  }
  .workspace-footnote {
    font-size: 8px;
  }
  .assistant-result {
    padding: 15px;
  }
  .report-preview {
    height: 550px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .working-dot {
    animation: none;
  }
  .suggestion-grid button {
    transition: none;
  }
}
</style>
