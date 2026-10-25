import request from "@/lib/request"

// 获取手机/邮箱验证码
export const fetchSmsCode = (account) => {
    return request({
        url: "/user/sendVerificationCode",
        method: "GET",
        params: {
            account
        }
    })
}

// 注册
export const fetchRegister = (data) => {
    return request({
        url: "/user/register",
        method: "POST",
        data
    })
}

// 密码登录
export const fetchLoginPsw = (data) => {
    return request({
        url: "/user/loginPassword",
        method: "POST",
        data
    })
}

// 验证码登录
export const fetchLoginSms = (data) => {
    return request({
        url: "/user/loginCode",
        method: "POST",
        data
    })
}

// 退出登录
export const fetchLogout = (userId) => {
    return request({
        url: "/user/logout",
        method: "GET",
        params: {
            userId
        }
    })
}

// 关注，userId:用户id，creatorId:被关注用户id
export const fetchFocus = (userId = '', creatorId) => {
    return request({
        url: "/video/user/follow",
        method: "POST",
        data: {
            userId,
            creatorId
        }
    })
}

// 取消关注，userId:用户id，creatorId:被关注用户id
export const fetchUnfocus = (userId, creatorId) => {
    return request({
        url: "/video/user/chanel/follow",
        method: "POST",
        data: {
            userId,
            creatorId
        }
    })
}

// 获取关注列表
export const fetchFocusList = (userId) => {
    return request({
        url: "/video/user/following/list",
        method: "GET",
        params: {
            userId
        }
    })
}

// 获取粉丝列表
export const fetchFansList = (userId) => {
    return request({
        url: "/video/user/followers/list",
        method: "GET",
        params: {
            userId
        }
    })
}

// 获取用户信息
export const fetchUserInfo = (userId = '', creatorId) => {
    return request({
        url: "/user/info",
        method: "POST",
        data: {
            userId, creatorId
        }
    })
}

// 全局搜索
export const fetchSearch = (keyword) => {
    return request({
        url: "/searchAll",
        method: "GET",
        params: {
            keyword
        }
    })
}

// 搜索用户
export const fetchSearchUser = (keyword) => {
    return request({
        url: "/search/user",
        method: "GET",
        params: {
            keyword
        }
    })
}

// 搜索视频
export const fetchSearchVideo = (keyword) => {
    return request({
        url: "/search/video",
        method: "GET",
        params: {
            keyword
        }
    })
}