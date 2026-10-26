import { onScopeDispose } from 'vue';
import { getAgentRun, readAgentEvents } from '@/api/agent';
import { createRunMonitor } from '@/agent/run-monitor.mjs';

export function useAgentRunMonitor(options) {
  const monitor = createRunMonitor({ getAgentRun, readAgentEvents, ...options });
  onScopeDispose(monitor.stop);
  return monitor;
}
