import request from "@/lib/request"

// 检查文件是否存在
export const checkUploadFile = (fileHash) => {
    return request({
        url: "/video/file/check",
        method: "GET",
        params: {
            fileHash
        }
    })
}

// 获取上传文件分片进度
export const getUploadFileProgress = (fileHash) => {
    return request({
        url: "/video/file/get/upload/progress",
        method: "GET",
        params: {
            fileHash
        }
    })
}

// 获取分片上传的链接
export const getUploadFileUrl = (chunkCount, fileHash, suffixName) => {
    return request({
        url: "/video/file/get/upload/urls",
        method: "POST",
        data: {
            chunkCount,
            fileHash,
            fileType: suffixName
        }
    })
}

// 合并分片
export const mergeFileChunk = (chunkCount, fileHash, suffixName = "mp4") => {
    return request({
        url: "/video/file/merge/chunk",
        method: "POST",
        data: {
            chunkCount,
            fileHash,
            fileType: suffixName
        }
    })
}

// 投稿
export const submitFile = (videoInfo) => {
    return request({
        url: "/video/submit",
        method: "POST",
        data: videoInfo,
        headers: {
            'Content-Type': 'multipart/form-data',
        }
    })
}

// 上传分片
export const uploadFileChunk = (file, url, fileType = "") => {
    return request({
        url,
        method: "PUT",
        data: file,
        headers: {
            "Content-Type": fileType || "application/octet-stream"
        }
    })
}