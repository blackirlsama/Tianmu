

import { terminal, progressLabel, reportPath } from './protocol.mjs';

/** Owns subscription/polling lifecycle; UI state is supplied by the conversation view. */
export function createRunMonitor({ getAgentRun, readAgentEvents, applySnapshot, notice }) {
let monitor, pollTimer;
function stop() { monitor?.abort(); clearTimeout(pollTimer); }
function start(turn) {
  stop();
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
        if (signal.aborted) return;
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
        let timer;
        const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
        timer = setTimeout(finish, 1500);
        if (signal.aborted) finish(); else signal.addEventListener('abort', finish, { once: true });
      });

    }
  };
  return stream();
}
return { start, stop, get signal() { return monitor?.signal; } };
}
