// Accept the previous single-item envelope as well as room batches during rollout.
export function danmakuItems(message) {
  const items = message?.type === 5 ? message.data : message?.type === 2 ? [message.data] : []
  if (!Array.isArray(items) || items.length > 1000) return []
  return items.filter(item => item && typeof item.bulletId === 'string' && typeof item.userId === 'string' &&
    typeof item.text === 'string' && Number.isFinite(item.playbackTime) && item.playbackTime >= 0)
    .map(item => ({ bulletId: item.bulletId, userId: item.userId, text: item.text, time: item.playbackTime }))
}

export function splitDanmaku(items, userId) {
  const own = items.filter(item => item.userId === String(userId))
  return { own, others: items.filter(item => item.userId !== String(userId)) }
}
