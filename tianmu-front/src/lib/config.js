const websocketProtocol = window.location.protocol === "https:" ? "wss:" : "ws:"
const websocketHost = process.env.NODE_ENV === 'production' ? window.location.host : `${window.location.hostname}:9101`
const danmuWSBaseUrl = process.env.VUE_APP_DANMU_WS_BASE_URL ||
    `${websocketProtocol}//${websocketHost}/ws/bulletScreen/`

export function getDanmuWSUrl(videoId) {
    return `${danmuWSBaseUrl}${videoId}`
}

export function getSearchHistoryKey() {
    return "TIANMU_searchHistories"
}

export const TIANMU_USER = "TIANMU_USER"
export const TIANMU_LOGIN = "TIANMU_LOGIN"
