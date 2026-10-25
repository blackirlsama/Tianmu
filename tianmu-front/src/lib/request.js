// 对于axios进行二次封装

import axios from 'axios'
import { useUserStore } from "@/store/user";
import { TIANMU_USER } from "@/lib/config"

// 利用axios.create方法创建一个axios实例，并设置请求基础路径，超时时间
const request = axios.create({
    baseURL: "/api",
})


// 设置请求拦截器
request.interceptors.request.use((config) => {
    const token = JSON.parse(localStorage.getItem(TIANMU_USER))?.token
    if (token && token !== '') {
        config.headers['Authorization'] = token;
    }
    return config;
})


// 设置响应拦截器
request.interceptors.response.use((response) => {
    // 如果登录信息已过期，提示重新登录，清除本地的用户信息
    if (response.data.code === 401 || response.data.code === 50005 || response.data.code === 50004) {
        const userStore = useUserStore()
        userStore.logout()
        return Promise.reject(response.data.message)
    }
    // 状态码为60000时表示视频未上传过
    if (response.data && response.data.code !== 60000 && response.data.code !== 200) {
        return Promise.reject(response.data.message)
    }
    //响应拦截器成功的回调,一般会进行简化数据
    return response.data
}, (error) => {
    return Promise.reject(error)
})

export default request