import { defineStore } from "pinia";
import { getSearchHistoryKey } from "@/lib/config.js";


export const useSearchStore = defineStore("search", {
    state: () => ({
        // 搜索历史记录
        searchHistories: JSON.parse(localStorage.getItem(getSearchHistoryKey())) || [],
    }),
    actions: {
        addHistory(keyword) {
            const index = this.searchHistories.indexOf(keyword);
            if (index != -1) {
                // 值已存在，移除该值
                this.searchHistories.splice(index, 1);
            }
            this.searchHistories.unshift(keyword); // 在列表开头插入新记录
            this.saveToLocalStorage();
        },
        saveToLocalStorage() {
            localStorage.setItem(
                getSearchHistoryKey(),
                JSON.stringify(this.searchHistories)
            );
        },

        // 删除单个搜索历史
        removeHistory(index) {
            this.searchHistories.splice(index, 1);
            this.saveToLocalStorage();
        },

        // 清空全部搜索历史
        removeAllHistories() {
            this.searchHistories = [];
            localStorage.removeItem(getSearchHistoryKey());
        },
    },
});
