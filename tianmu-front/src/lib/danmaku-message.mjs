// Only this private send envelope contains authorization; it never belongs in a WS URL.
export function danmakuMessage(danmu, token) {
  if (typeof token !== 'string' || !token.trim()) throw new Error('请先登录再发送弹幕')
  if (typeof danmu?.content !== 'string' || !danmu.content.trim() || danmu.content.length > 255 ||
      !Number.isFinite(danmu.playbackTime) || danmu.playbackTime < 0 ||
      typeof danmu.videoId !== 'string' || !/^[1-9]\d{0,18}$/.test(danmu.videoId) ||
      (danmu.videoId.length === 19 && danmu.videoId > '9223372036854775807')) {
    throw new Error('请检查弹幕内容、播放时间和视频')
  }
  return JSON.stringify({authorization: token, videoId: danmu.videoId,
    content: danmu.content, playbackTime: danmu.playbackTime})
}
