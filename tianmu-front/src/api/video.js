import request from "@/lib/request"

// 获取视频列表
export const fetchVideoList = (current, pageSize) => {
    return request({
        url: "/video/list",
        method: "GET",
        params: {
            current,
            pageSize
        }
    })
}

// 获取视频详情
export const fetchVideoDetail = (userId, videoId) => {
    return request({
        url: `/video/detail`,
        method: "POST",
        data: {
            userId,
            videoId
        }
    })
}

// 点赞视频
export const fetchLikeVideo = (userId, videoId) => {
    return request({
        url: "/video/like",
        method: "POST",
        data: {
            userId,
            videoId
        }
    })
}

// 取消点赞视频
export const fetchLikeVideoCancel = (id, videoId) => {
    return request({
        url: "/video/cancel/like",
        method: "POST",
        data: {
            id,
            videoId
        }
    })
}

// 收藏视频
export const fetchCollectVideo = (userId, videoId) => {
    return request({
        url: "/video/favorite",
        method: "POST",
        data: {
            userId,
            videoId
        }
    })
}

// 取消收藏视频
export const fetchCollectVideoCancel = (id, videoId) => {
    return request({
        url: "/video/cancel/favorite",
        method: "POST",
        data: {
            id,
            videoId
        }
    })
}

// 投币视频
export const fetchCoinVideo = (userId, videoId) => {
    return request({
        url: "/video/coin",
        method: "POST",
        data: {
            userId,
            videoId
        }
    })
}

// 一键三连
export const fetchTripleVideo = (userId, videoId) => {
    return request({
        url: "/video/triple/action",
        method: "POST",
        data: {
            userId,
            videoId
        }
    })
}

// 发表视频评论
export const fetchSendComment = ({ content, userId, videoId, parentCommentId = "" }) => {
    return request({
        url: "/video/create/comment",
        method: "POST",
        data: {
            content,
            userId,
            videoId,
            parentCommentId
        }
    })
}

// 删除视频评论
export const fetchDeleteComment = (id, videoId) => {
    return request({
        url: "/video/delete/comment",
        method: "POST",
        data: {
            id,
            videoId
        }
    })
}

// 获取评论列表
export const fetchCommentList = (videoId) => {
    return request({
        url: "/video/comment/list",
        method: "GET",
        params: {
            videoId
        }
    })
}

// 获取投稿视频信息列表
export const fetchSbmVideoList = (userId) => {
    return request({
        url: "/video/submit/list",
        method: "GET",
        params: {
            userId
        }
    })
}

// 删除弹幕
export const fetchDelDanmu = ({ bulletId, userId, videoId, content }) => {
    return request({
        url: "/video/delete/bullet",
        method: "POST",
        data: {
            bulletId,
            userId,
            videoId,
            content
        }
    })
}

// 获取点赞视频信息列表
export const fetchLikeVideoList = (userId) => {
    return request({
        url: "/video/like/list",
        method: "GET",
        params: {
            userId
        }
    })
}

// 获取投币视频信息列表
export const fetchCoinVideoList = (userId) => {
    return request({
        url: "/video/coin/list",
        method: "GET",
        params: {
            userId
        }
    })
}
// 获取收藏视频信息列表
export const fetchColtVideoList = (userId) => {
    return request({
        url: "/video/favorite/list",
        method: "GET",
        params: {
            userId
        }
    })
}

// 视频搜索
export const fetchSearch = (keyword) => {
    return request({
        url: "/search/video",
        method: "GET",
        params: {
            keyword
        }
    })
}

// 获取所有分区
export const fetchVideoCate = () => {
    return request({
        url: "/category",
        method: "GET"
    })
}

// 获取分区视频
export const fetchVideoCateList = (categoryId) => {
    return request({
        url: "/category/list",
        method: "GET",
        params: {
            categoryId
        }
    })
}
