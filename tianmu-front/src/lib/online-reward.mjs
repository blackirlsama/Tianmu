// Presence is a visible authenticated page. The server owns the reward date and deduplication.
export function createOnlineRewardMonitor({ session, visible, request, apply, every = setInterval, clear = clearInterval }) {
  let active = null
  let timer = null
  let stopped = true

  async function refresh() {
    if (stopped) return
    const identity = session()
    if (!identity || !visible()) {
      active?.controller.abort()
      active = null
      return
    }
    if (active?.identity === identity) return
    active?.controller.abort()
    const pending = { identity, controller: new AbortController() }
    active = pending
    try {
      const result = await request(pending.controller.signal)
      if (!stopped && active === pending && session() === identity) apply(result)
    } catch {
      // A failed presence check must not disrupt browsing; retry on the next tick or focus.
    } finally {
      if (active === pending) active = null
    }
  }

  return {
    refresh,
    start() {
      if (!stopped) return
      stopped = false
      timer = every(refresh, 60_000)
      refresh()
    },
    stop() {
      stopped = true
      clear(timer)
      active?.controller.abort()
      active = null
    },
  }
}
