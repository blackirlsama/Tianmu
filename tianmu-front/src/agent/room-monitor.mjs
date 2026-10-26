// Serial polling with cancellation and generation fencing; delayed private data cannot cross sessions.
export function createRoomMonitor({ fetchSnapshot, onSnapshot, onError, interval = 15000, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let generation = 0, controller, timer, active = false
  function stop() { generation++; active = false; clearTimer(timer); controller?.abort(); controller = null }
  async function tick(ticket) {
    const request = new AbortController(); controller = request
    try {
      const snapshot = await fetchSnapshot(request.signal)
      if (ticket === generation && active) onSnapshot(snapshot)
    } catch (error) {
      if (ticket !== generation || error.name === 'AbortError') return
      if ([401, 403].includes(error.status)) stop()
      onError(error)
    } finally {
      if (ticket === generation && active) timer = setTimer(() => tick(ticket), interval)
    }
  }
  return { start() { stop(); active = true; void tick(generation) }, stop }
}
