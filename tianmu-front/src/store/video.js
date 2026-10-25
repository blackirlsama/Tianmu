import { defineStore } from "pinia";
import { fetchVideoCate } from "@/api/video"
export const useVideoStore = defineStore("video", {
    state: () => ({
        // 视频分类数据
        categoryList: []
    }),
    actions: {
        async getCategoryList() {
            try {
                const res = await fetchVideoCate()
                if (res.code === 200) {
                    this.categoryList = res.data
                }
            } catch (error) {
                console.log("获取视频分类失败：" + error)
            }
        }
    },
});
