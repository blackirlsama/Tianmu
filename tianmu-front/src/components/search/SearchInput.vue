<template>
  <div class="search-input">
    <div class="flex_center">
      <div class="search-input-wrap flex_between" :class="{ active: isActive }">
        <svg
          t="1708852350668"
          class="search-icon"
          viewBox="0 0 1024 1024"
          version="1.1"
          xmlns="http://www.w3.org/2000/svg"
          p-id="4079"
        >
          <path
            d="M941.6 874L763.7 696.1c20.6-26.7 37.6-55.9 50.8-87.2 20.3-48.3 30.6-99.6 30.6-152.4s-10.3-104.1-30.6-152.4c-19.6-46.6-47.7-88.5-83.5-124.5s-77.5-64.2-123.9-84C559 75.3 507.9 64.9 455.3 64.9S351.6 75.3 303.5 95.8c-46.4 19.7-88.1 48-123.9 84-35.8 36-63.9 77.8-83.5 124.5-20.3 48.3-30.6 99.6-30.6 152.4s10.3 104.1 30.6 152.4c19.6 46.6 47.7 88.5 83.5 124.5s77.5 64.2 123.9 84c48.1 20.5 99.2 30.8 151.8 30.8S559 838 607.1 817.6c31.7-13.5 61.2-30.9 88.1-52l177.6 177.6c18.9 18.9 49.9 18.9 68.9 0 18.9-19.3 18.9-50.3-0.1-69.2zM455.3 768.3c-170.9 0-310-139.8-310-311.7s139-311.7 310-311.7c170.9 0 310 139.8 310 311.7s-139.1 311.7-310 311.7z"
            p-id="4080"
          ></path>
        </svg>
        <input
          class="search-input-el"
          type="text"
          placeholder="输入关键字搜索"
          v-model="input"
          @focus="searchPopShow()"
          @keyup.enter="goSearch"
        />
        <div
          class="nav-search-clean"
          :style="input == '' ? 'display: none;' : ''"
          @click.stop="input = ''"
        >
          <el-icon size="20"><CircleCloseFilled /></el-icon>
        </div>
        <button
          class="v-ui-button v-ui-button--pink v-ui-button--lg search-button"
          @click="goSearch"
        >
          搜索
        </button>
        <!-- 气泡框 -->
        <div class="search-panel" v-if="isActive && input === ''">
          <div class="history">
            <div class="header">
              <div class="title">搜索历史</div>
              <div
                v-if="searchHistories.length > 0"
                class="clear"
                @click.stop="removeAllHistories"
              >
                清空
              </div>
            </div>
            <div class="histories-wrap">
              <div class="histories">
                <template v-if="searchHistories.length > 0">
                  <div
                    class="history-item"
                    v-for="(item, index) in searchHistories"
                    :key="index"
                  >
                    <div
                      class="history-text"
                      @click.stop="clickItemToSearch(item)"
                    >
                      {{ item }}
                    </div>
                    <div class="close" @click.stop="removeHistory(index)">
                      <i class="iconfont icon-close"></i>
                    </div>
                  </div>
                </template>
                <div style="text-align: center; width: 100%" v-else>
                  暂无搜索记录
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { useSearchStore } from "@/store/search.js";
import { mapState, mapActions } from "pinia";
export default {
  name: "SearchInput",
  data() {
    return {
      // 是否正在输入，控制气泡框显隐
      isActive: false,
      // 输入内容
      input: "",
    };
  },
  props: {
    // 用于初始化输入的内容
    keyword: {
      type: String,
      default() {
        return "";
      },
    },
  },
  computed: {
    ...mapState(useSearchStore, ["searchHistories"]),
  },
  methods: {
    ...mapActions(useSearchStore, [
      "addHistory",
      "removeHistory",
      "removeAllHistories",
    ]),
    // 前往搜索的回调
    goSearch() {
      this.searchPopHide();
      let input = this.input.trim();

      if (input === "") {
        // 输入空白符跳转搜索首页
        this.$router.push("/search");
      } else {
        // 否则就跳搜索详情页
        if (this.$route.path.startsWith("/search/user")) {
          this.$router.push(`/search/user?keyword=${input}`);
        } else {
          this.$router.push(`/search/video?keyword=${input}`);
        }
      }

      if (!input) {
        return;
      }
      // 把关键词加入搜索历史中
      this.addHistory(input);
    },

    // 显示搜索气泡框
    searchPopShow() {
      this.isActive = true;
    },

    // 隐藏搜索气泡框
    searchPopHide() {
      this.isActive = false;
    },

    // 点击搜索框和气泡框外的空白处关闭气泡
    handleOutsideClick(event) {
      const searchElement = document.querySelector(".search-input-wrap");
      if (!searchElement.contains(event.target)) {
        this.searchPopHide();
      }
    },

    // 点击条目搜索
    clickItemToSearch(value) {
      this.input = value;
      this.goSearch();
    },
  },
  mounted() {
    // 页面渲染后创建点击事件的监听器
    window.addEventListener("click", this.handleOutsideClick);
    this.input = this.keyword;
  },
  beforeUnmount() {
    // 页面结束渲染前销毁事件的监听器
    window.removeEventListener("click", this.handleOutsideClick);
  },
};
</script>

<style scoped>
.search-input {
  --search_input_width: 640px;
  --search_input_height: 48px;
  --search_panel_top: 52px;
  --search_input_max_h: auto;
  --search_button_height: 38px;
  height: var(--search_input_height);
}

.search-input-wrap {
  position: relative;
  width: var(--search_input_width);
  height: var(--search_input_height);
  max-height: var(--search_input_max_h);
  margin: 0 auto;
  padding: 5px;
  background: var(--bg2);
  border: 1px solid var(--line_light);
  border-radius: 6px;
  transition: all 0.2s;
}

.search-input-wrap:hover,
.search-input-wrap.active {
  background: var(--bg1);
}

.search-input-wrap.active {
  border-color: var(--main-color);
}

.search-icon {
  width: 20px;
  fill: var(--main-color);
  margin-left: 15px;
  margin-right: 10px;
}

.search-input-el {
  width: calc(100% - 160px);
  height: 36px;
  font-size: 18px;
  color: var(--text1);
  margin-right: 15px;
  border: none;
  outline: none;
  background: transparent;
}

.nav-search-clean {
  position: absolute;
  width: 20px;
  height: 20px;
  right: 115px;
  cursor: pointer;
  color: var(--graph_weak);
}

.nav-search-clean:hover {
  color: var(--graph_icon);
}

.search-button {
  width: 100px;
  height: var(--search_button_height);
}

.search-panel {
  position: absolute;
  left: 0;
  z-index: 998;
  width: 100%;
  max-height: 612px;
  overflow-y: auto;
  background: var(--bg1);
  border: 1px solid var(--line_regular);
  border-top: none;
  border-radius: 0 0 8px 8px;
  padding: 13px 0 16px;
  -webkit-font-smoothing: antialiased;
  top: var(--search_panel_top);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
}

.search-input-wrap .header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}

.search-input-wrap .header .title {
  height: 24px;
  font-size: 16px;
  line-height: 24px;
}

.search-input-wrap .header .clear {
  font-size: 12px;
  line-height: 15px;
  height: 15px;
  color: var(--text3);
  cursor: pointer;
}

.search-input-wrap .header .clear:hover {
  color: var(--text2);
}

.search-input-wrap .histories-wrap {
  padding: 0 16px;
  overflow: hidden;
}

.search-input-wrap .histories {
  display: flex;
  flex-wrap: wrap;
  margin-top: 12px;
  margin-right: -10px;
  margin-bottom: 4px;
}

.search-input-wrap .histories .history-item {
  position: relative;
  box-sizing: border-box;
  height: 30px;
  padding: 7px 10px 8px;
  font-size: 12px;
  line-height: 15px;
  background: var(--graph_bg_thin);
  border-radius: 4px;
  margin-right: 10px;
  margin-bottom: 10px;
  cursor: pointer;
}

.search-input-wrap .histories .history-item .history-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 96px;
}

.search-input-wrap .histories .history-item .history-text:hover {
  color: var(--main-color);
}

.search-input-wrap .histories .history-item .close {
  display: none;
  box-sizing: border-box;
  position: absolute;
  width: 16px;
  height: 16px;
  top: -6px;
  right: -6px;
  padding: 2px;
}

.search-input-wrap .histories .history-item:hover .close {
  display: block;
}

.search-input-wrap .histories .history-item .close i {
  font-size: 10px;
  color: #ccc;
  background-color: #fff;
  border-radius: 50%;
}

@media (max-width: 1439.9px) {
  .search-input {
    --search_input_width: 480px;
    --search_input_height: 44px;
    --search_panel_top: 46px;
    --search_button_height: 34px;
  }
}

@media (min-width: 1700px) {
  .search-input {
    --search_input_height: 54px;
    --search_panel_top: 58px;
    --search_button_height: 40px;
  }
}
</style>