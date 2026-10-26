import { computed } from 'vue';
import { validRunId } from '@/agent/protocol.mjs';

/** User-scoped local indexes contain neither authentication tokens nor report bodies. */
export function useAgentHistory({ ownerId, history, turns }) {
const historyKey = computed(() => `TIANMU_agent_runs_${ownerId.value}`);
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
return { readHistory, saveHistory };
}
