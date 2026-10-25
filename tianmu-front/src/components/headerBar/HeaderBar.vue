<template>
  <div class="header-bar" :class="isFixHeaderBar ? 'slide-down' : ''">
    <!-- 左边导航栏 -->
    <div class="left-entry">
      <!-- <div
                class="entry-title"
            >
                <picture class="logo">
                    <img src="@/assets/img/icon_hot.png" alt="">
                </picture>
                <span>首页</span>
                <i class="iconfont icon-arrow-down"></i>
            </div> -->
      <div class="entry-title" @click="$router.push('/')">
        <i class="iconfont icon-tv" style="margin-right: 5px"></i>
        <span>首页</span>
      </div>
      <!-- <div class="default-entry" @click="noPage">
        <span>直播</span>
      </div> -->
    </div>
    <!-- 中间搜索框 -->
    <div class="center-search-container">
      <div class="center-search__bar">
        <!-- 输入框 -->
        <div id="nav-searchform" ref="searchForm">
          <div class="nav-search-content">
            <el-input
              class="nav-search-input"
              :class="isSearchPopShow ? 'nav-search-input-active' : ''"
              v-model="searchInput"
              placeholder="请输入搜索内容"
              @focus="searchPopShow()"
              @keyup.enter="goSearch"
            ></el-input>
          </div>
          <!-- 清除输入框内容按钮 -->
          <div
            class="nav-search-clean"
            :style="searchInput == '' ? 'display: none;' : ''"
            @click.stop="searchInput = ''"
          >
            <el-icon size="16"><CircleCloseFilled /></el-icon>
          </div>
          <!-- 搜索按钮 -->
          <div class="nav-search-btn" @click="goSearch">
            <i class="iconfont icon-sousuo"></i>
          </div>
        </div>

        <!-- 搜索历史气泡框 -->
        <div
          class="search-panel"
          v-if="isSearchPopShow && searchInput == ''"
          ref="searchPop"
        >
          <div class="history">
            <div class="header">
              <div class="title">搜索历史</div>
              <div class="clear" @click.stop="removeAllHistories">清空</div>
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

    <!-- 右边导航栏 -->
    <div class="right-entry">
      <!-- 未登录状态 -->
      <div class="header-avatar-wrap" v-if="!isLogin">
        <div class="default-login" @click="updateDialogVisible(true)">登录</div>
      </div>
      <!-- 登录后显示头像 -->
      <div
        v-else
        class="header-avatar-wrap"
        @mouseenter="handleMouseEnter"
        @mouseleave="handleMouseLeave"
      >
        <a
          :href="`/user/${user.userId}`"
          target="_blank"
          class="header-avatar-wrap--container mini-avatar--small"
        >
          <picture class="v-img">
            <img :src="headerAvatarUrl" :alt="`${user.nickname}的头像`" @error="headerAvatarFailed = true" />
          </picture>
        </a>
        <div class="v-popover to-bottom">
          <div
            class="avatar-panel-popover"
            :class="isPopoverShow ? 'popShow' : 'popHide'"
            :style="{ display: popoverDisplay }"
          >
            <a :href="`/user/${user.userId}`" target="_blank" class="nickname">
              <span>{{ user.nickname }}</span>
            </a>
            <div class="vip-level-tag">
              <div class="gender female" v-if="user.gender == 0">
                <el-icon size="12"><Female /></el-icon>
              </div>
              <div class="gender male" v-if="user.gender == 1">
                <el-icon size="12"><Male /></el-icon>
              </div>
              <div class="gender u" v-if="user.gender == 2">
                <i
                  class="iconfont icon-gender-unknown"
                  style="font-size: 20px"
                ></i>
              </div>
            </div>
            <div class="coins">
              <span class="coins-text">硬币: </span>
              <span class="coins-num">{{ user.coinCount }}</span>
            </div>
            <div class="counts">
              <a
                :href="`/user/${user.userId}/relation/follow`"
                target="_blank"
                class="counts-item"
              >
                <div class="count-num">{{ handleNum(user.following) }}</div>
                <div class="count-text">关注</div>
              </a>
              <a
                :href="`/user/${user.userId}/relation/fans`"
                target="_blank"
                class="counts-item"
              >
                <div class="count-num">{{ handleNum(user.followers) }}</div>
                <div class="count-text">粉丝</div>
              </a>
              <a
                :href="`/user/${user.userId}/upload`"
                target="_blank"
                class="counts-item"
              >
                <div class="count-num">{{ handleNum(user.videoCount) }}</div>
                <div class="count-text">投稿</div>
              </a>
            </div>
            <div
              class="single-item middle"
              @click="openNewPage(`/user/${user.userId}`)"
            >
              <div class="single-item-left">
                <el-icon size="16"><User /></el-icon>
                <span>个人中心</span>
              </div>
              <el-icon><ArrowRightBold /></el-icon>
            </div>
            <!-- <div
              class="single-item middle"
              @click="openNewPage('/platform/upload-manager')"
            >
              <div class="single-item-left">
                <el-icon size="16"><Document /></el-icon>
                <span>投稿管理</span>
              </div>
              <el-icon><ArrowRightBold /></el-icon>
            </div> -->
            <div class="placeholder"></div>
            <div class="single-item logout" @click="handleLogout">
              <i class="iconfont icon-logout"></i>
              <span>退出登录</span>
            </div>
          </div>
        </div>
      </div>
      <!-- 动态 -->
      <div class="v-popover-wrap">
        <VPopover pop-style="padding-top: 17px;">
          <template #reference>
            <div
              class="right-entry--outside"
              @click="openNewPage(`/user/${user.userId}/upload`)"
            >
              <i class="iconfont icon-fengche"></i>
              <span>动态</span>
            </div>
          </template>
          <template #content>
            <div class="v-popover-content" v-if="isLogin">
              <div class="video-panel">
                <template v-if="submitVideoList.length > 0">
                  <!-- 视频卡片 -->
                  <a
                    :href="`/video/${item.videoId}`"
                    class="video-item"
                    v-for="item in submitVideoList.slice(0, 20)"
                    :key="item.videoId"
                    target="_blank"
                  >
                    <VideoSmallCard :item="item"></VideoSmallCard
                  ></a>
                </template>
                <el-empty v-else description="暂无数据" />
              </div>
              <div class="content-bottom">
                <a :href="`/user/${user.userId}/upload`" target="_blank"
                  >查看全部</a
                >
              </div>
            </div>
            <div class="not-login" v-else>
              <p class="not-login-tips">登录即可查看关注动态</p>
              <div class="not-login-btn" @click="openNewPage">立即登录</div>
            </div>
          </template>
        </VPopover>
      </div>
      <!-- 收藏 -->
      <div class="v-popover-wrap">
        <VPopover
          :pop-style="
            isLogin
              ? 'padding-top: 17px; margin-left: -100px;'
              : 'padding-top: 17px;'
          "
        >
          <template #reference>
            <div
              class="right-entry--outside"
              @click="openNewPage(`/user/${user.userId}/favlist`)"
            >
              <i class="iconfont icon-shoucang"></i>
              <span>收藏</span>
            </div>
          </template>
          <template #content>
            <div class="v-popover-content" v-if="isLogin">
              <div class="video-panel">
                <template v-if="coltVideoList.length > 0">
                  <!-- 视频卡片 -->
                  <a
                    :href="`/video/${item.videoId}`"
                    class="video-item"
                    v-for="item in coltVideoList.slice(0, 20)"
                    :key="item.videoId"
                    target="_blank"
                  >
                    <VideoSmallCard :item="item"></VideoSmallCard
                  ></a>
                </template>
                <el-empty v-else description="暂无数据" />
              </div>
              <div class="content-bottom">
                <a :href="`/user/${user.userId}/favlist`" target="_blank"
                  >查看全部</a
                >
              </div>
            </div>
            <div class="not-login" v-else>
              <p class="not-login-tips">登录即可查看我的收藏</p>
              <div class="not-login-btn" @click="openNewPage">立即登录</div>
            </div>
          </template>
        </VPopover>
      </div>
      <!-- 历史 -->
      <!-- <div class="v-popover-wrap">
        <VPopover
          :pop-style="
            isLogin
              ? 'padding-top: 17px; margin-left: -50px;'
              : 'padding-top: 17px;'
          "
        >
          <template #reference>
            <div
              class="right-entry--outside"
              @click="openNewPage(`/user/${user.userId}/history`)"
            >
              <i class="iconfont icon-lishijilu"></i>
              <span>历史</span>
            </div>
          </template>
          <template #content>
            <div style="height: 556.6px; width: 371.6px" v-if="isLogin"></div>
            <div class="not-login" v-else>
              <p class="not-login-tips">登录即可查看历史记录</p>
              <div class="not-login-btn" @click="openNewPage">立即登录</div>
            </div>
          </template>
        </VPopover>
      </div> -->
      <!-- 创作中心 -->
      <!-- <div class="right-entry-item" @click="openNewPage('/platform')">
        <div class="right-entry--outside">
          <i class="iconfont icon-dengpao"></i>
          <span>创作中心</span>
        </div>
      </div> -->
      <!-- 投稿 -->
      <div
        class="right-entry-item right-entry-item--upload"
        @click="openNewPage('/platform/upload')"
      >
        <div class="upload-buttom">
          <i class="iconfont icon-shangchuan"></i>
          <span>投稿</span>
        </div>
      </div>
    </div>
  </div>

  <!-- 登录注册对话框 -->
  <el-dialog
    v-model="dialogVisibleComputed"
    :close-on-click-modal="false"
    destroy-on-close
    align-center
  >
    <LoginRegister @loginSuccess="updateDialogVisible(false)"></LoginRegister>
  </el-dialog>
</template>

<script>
let inTimer; // 节流计时器
let outTimer;
import VPopover from "@/components/popover/VPopover.vue";
import LoginRegister from "@/components/loginRegister/LoginRegister.vue";
import { handleNum } from "@/lib/utils.js";
import { mediaUrl } from "@/lib/media";
import defaultAvatar from "@/assets/img/default-avatar.svg";
import { useUserStore } from "@/store/user.js";
import { mapState, mapActions } from "pinia";
import { fetchLogout } from "@/api/user.js";
import { ElMessage } from "element-plus";
import { useSearchStore } from "@/store/search.js";
import { fetchColtVideoList, fetchSbmVideoList } from "@/api/video.js";
import VideoSmallCard from "@/components/VideoSmallCard/VideoSmallCard.vue";

export default {
  name: "HeaderBarIndex",
  components: {
    VPopover,
    LoginRegister,
    VideoSmallCard,
  },
  data() {
    return {
      // 搜索框输入内容
      searchInput: "",
      // 头像气泡框的显隐
      popoverDisplay: "none",
      isPopoverShow: false,
      // 是否显示搜索气泡框
      isSearchPopShow: false,
      coltVideoList: [],
      submitVideoList: [],
      headerAvatarFailed: false,
    };
  },
  props: {
    // 是否是固钉导航栏
    isFixHeaderBar: {
      type: Boolean,
      default() {
        return false;
      },
    },
    // 是否显示搜索输入框
    isShowSearchInput: {
      type: Boolean,
      default() {
        return true;
      },
    },
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin", "dialogVisible"]),
    ...mapState(useSearchStore, ["searchHistories"]),
    headerAvatarUrl() {
      return this.user?.avatar && !this.headerAvatarFailed
        ? mediaUrl(this.user.avatar)
        : defaultAvatar;
    },
    // 创建计算属性实现双向绑定
    dialogVisibleComputed: {
      get() {
        return this.dialogVisible;
      },
      set(value) {
        this.updateDialogVisible(value); // 调用 action 修改状态
      },
    },
  },
  methods: {
    ...mapActions(useUserStore, ["logout", "updateDialogVisible"]),
    ...mapActions(useSearchStore, [
      "addHistory",
      "removeHistory",
      "removeAllHistories",
    ]),
    // 前往搜索的回调
    goSearch() {
      this.searchPopHide();
      let input = this.searchInput.trim();
      if (input === "") {
        // 输入空白符跳转搜索首页
        this.openNewPage("/search");
      } else {
        // 否则就跳搜索详情页
        this.openNewPage(`/search/video?keyword=${input}`);
      }
      if (!input) {
        return;
      }
      // 把关键词加入搜索历史中
      this.addHistory(input);
    },
    // 点击条目搜索
    clickItemToSearch(value) {
      this.searchInput = value;
      this.goSearch();
    },
    // 打开新标签页
    openNewPage(route) {
      if (!this.isLogin) {
        this.updateDialogVisible(true);
        return;
      }
      // 已经登录就跳转
      if (this.isLogin) {
        window.open(this.$router.resolve(route).href, "_blank");
      }
    },
    // 处理大于一万的数字
    handleNum(number) {
      return handleNum(number);
    },
    // 悬浮头像时，气泡的显隐
    handleMouseEnter() {
      clearTimeout(outTimer); // 这里要清除隐藏的计时器，否则在0.2秒内出入头像，会导致头像变大但气泡突然消失
      inTimer = setTimeout(() => {
        this.popoverDisplay = "";
        this.isPopoverShow = true;
      }, 100);
    },
    handleMouseLeave() {
      clearTimeout(inTimer); // 清除显示计时器防止快速经过头像时的气泡闪烁
      this.isPopoverShow = false;
      outTimer = setTimeout(() => {
        this.popoverDisplay = "none";
      }, 200);
    },
    async handleLogout() {
      try {
        const res = await fetchLogout(this.user.userId);
        if (res.code === 200) {
          this.logout();
          ElMessage.success("退出成功");
        }
      } catch (error) {
        ElMessage.error("退出失败：" + error);
      }
    },
    // 显示搜索气泡框
    searchPopShow() {
      this.isSearchPopShow = true;
    },
    // 隐藏搜索气泡框
    searchPopHide() {
      this.isSearchPopShow = false;
    },
    // 点击搜索框和气泡框外的空白处关闭气泡
    handleOutsideClick(event) {
      const formElement = this.$refs.searchForm; // 输入框元素
      const popoverElement = this.$refs.searchPop; // 气泡框元素
      if (!formElement || !popoverElement) return;
      if (
        !formElement.contains(event.target) &&
        !popoverElement.contains(event.target)
      ) {
        this.searchPopHide();
      }
    },
    // 获取收藏视频
    async getColtVideoList() {
      if (!this.isLogin || !this.user.userId) return;
      try {
        const res = await fetchColtVideoList(this.user.userId);
        if (res.code === 200) {
          this.coltVideoList = res.data;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
    // 获取投稿的视频
    async getSubmitVideoList() {
      if (!this.isLogin || !this.user.userId) return;
      try {
        const res = await fetchSbmVideoList(this.user.userId);
        if (res.code === 200) {
          this.submitVideoList = res.data;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
  },
  mounted() {
    this.getColtVideoList();
    this.getSubmitVideoList();
    // 页面渲染后创建点击事件的监听器
    window.addEventListener("click", this.handleOutsideClick);
    // 监听窗口大小变化，更新屏幕宽度
    window.addEventListener("resize", this.updateScreenWidth);
  },
  beforeUnmount() {
    // 页面结束渲染前销毁事件的监听器
    window.removeEventListener("click", this.handleOutsideClick);
    window.removeEventListener("resize", this.updateScreenWidth);
  },
  watch: {
    "user.avatar"() { this.headerAvatarFailed = false; },
  },
};
</script>

<style scoped>
.header-bar {
  box-sizing: border-box; /* 让内边距不增加宽度 */
  position: absolute;
  left: 0;
  top: 0;
  z-index: 1002;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  max-width: 2560px;
  width: 100%;
  height: 64px;
}

.left-entry {
  display: flex;
  align-items: center;
  flex-shrink: 0; /*容器空间不足时不缩小，即固定大小*/
  margin-right: 20px;
  border: 0;
  font-family: inherit;
  font-size: 100%;
  font-weight: 400;
  font-style: normal;
  line-height: 1.25;
}

.slide-down {
  position: fixed;
  top: 0;
  left: unset;
  animation: headerSlideDown 0.3s linear forwards;
  background: var(--bg1);
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.08);
  background: var(--bg1_float);
}

.logo {
  height: 40%;
  display: flex;
  align-items: center;
  margin-right: 10px;
}

.logo img {
  height: 100%;
}

.icon-xiajiantou {
  margin-left: 5px;
}

.entry-title {
  display: flex;
  align-items: center;
}

.entry-title .icon-xiajiantou {
  transition: transform 0.3s;
  transform: rotate(0);
  font-weight: 600;
}

.arrow-down {
  transform: rotate(180deg) !important;
}

.default-entry span {
  display: inline-block;
  position: relative;
}

.slide-down .entry-title,
.slide-down .default-entry {
  color: var(--text1);
}

.entry-title,
.default-entry {
  height: 64px;
  line-height: 64px;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  margin-right: 15px;
}

.center-search-container {
  flex: 1 auto; /* 宽度占80% 居中*/
  height: 40px;
}

.center-search-container .is-focus {
  box-shadow: 0 0 30px rgba(0, 0, 0, 0.1);
  border-radius: 8px;
}

.header-bar .center-search-container .center-search__bar {
  position: relative;
  margin: 0 auto;
  min-width: 181px;
  max-width: 500px;
}

.is-focus #nav-searchform {
  background-color: #fff;
  border-bottom: none;
}

#nav-searchform {
  display: flex;
  align-items: center;
  padding: 0 48px 0 4px;
  position: relative;
  z-index: 1;
  overflow: hidden;
  border: 1px solid var(--line_regular);
  height: 40px;
  border-radius: 8px;
  background-color: var(--graph_bg_regular);
  opacity: 0.9;
  transition: background-color 0.3s;
}

.nav-searchform-active {
  transition: background-color 0s !important;
  border-radius: 8px 8px 0 0 !important;
}

#nav-searchform:hover {
  background-color: #fff;
  opacity: 1;
}

.nav-search-content {
  flex: 1;
}

.nav-search-input /deep/ .el-input__wrapper {
  background-color: #ffffff00;
  border-radius: 6px;
  box-shadow: none;
  padding: 1px 30px 1px 11px;
}

.nav-search-input-active /deep/ .el-input__wrapper {
  background-color: #0000001a;
}

.nav-search-input /deep/ .el-input__inner {
  color: var(--text2);
}

.nav-search-input /deep/ .el-input__inner:focus {
  color: var(--text1);
}

.nav-search-clean {
  position: absolute;
  width: 16px;
  height: 16px;
  right: 55px;
  cursor: pointer;
  color: var(--graph_weak);
}

.nav-search-clean:hover {
  color: var(--graph_icon);
}

.nav-search-btn {
  position: absolute;
  right: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 6px;
  color: var(--text1);
  line-height: 32px;
  cursor: pointer;
  transition: background-color 0.3s;
}

.nav-search-btn:hover {
  background-color: var(--graph_bg_thick);
}

.icon-sousuo {
  font-size: 18px;
}

.header-bar .search-panel {
  width: 100%;
  max-height: 612px;
  overflow-y: auto;
  background: var(--bg1);
  border: 1px solid var(--line_regular);
  border-top: none;
  border-radius: 0 0 8px 8px;
  padding: 13px 0 16px;
  -webkit-font-smoothing: antialiased;
}

.header-bar .header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}

.header-bar .header .title {
  height: 24px;
  font-size: 16px;
  line-height: 24px;
}

.header-bar .header .clear {
  font-size: 12px;
  line-height: 15px;
  height: 15px;
  color: var(--text3);
  cursor: pointer;
}

.header-bar .header .clear:hover {
  color: var(--text2);
}

.header-bar .histories-wrap {
  padding: 0 16px;
  overflow: hidden;
}

.header-bar .histories {
  display: flex;
  flex-wrap: wrap;
  margin-top: 12px;
  margin-right: -10px;
  margin-bottom: 4px;
}

.header-bar .histories .history-item {
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

.header-bar .histories .history-item .history-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 96px;
  color: var(--text2);
}

.header-bar .histories .history-item .history-text:hover {
  color: var(--brand_blue);
}

.header-bar .histories .history-item .close {
  display: none;
  box-sizing: border-box;
  position: absolute;
  width: 13px;
  height: 13px;
  top: -6px;
  right: -3px;
  padding: 2px;
}

.header-bar .histories .history-item:hover .close {
  display: block;
}

.header-bar .histories .history-item .close i {
  font-size: 10px;
  color: #ccc;
  background: #fff;
  border-radius: 50%;
}

.header-bar .history-fold {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 70px;
  margin: 0 auto 14px;
  color: var(--text3);
  cursor: pointer;
}

.header-bar .history-fold:hover {
  color: var(--brand_blue);
}

.header-bar .fold-text {
  font-size: 12px;
  line-height: 15px;
  height: 15px;
}

.header-bar .trendings-double {
  display: flex;
}

.header-bar .trendings-double .trendings-col {
  flex: 1;
}

.header-bar .trendings-double .trendings-col:first-child {
  margin-right: 10px;
}

.header-bar .trending-wrap {
  box-sizing: border-box;
  height: 38px;
  display: flex;
  align-items: center;
  cursor: pointer;
  padding-left: 16px;
}

.header-bar .trending-item:hover {
  background-color: var(--graph_bg_thick);
}

.header-bar .trending-item .trendings-rank {
  margin-right: 10px;
  color: var(--text3);
}

.header-bar .trending-item .topThree {
  color: var(--text2) !important;
}

.header-bar .trending-item .trendings-text {
  font-size: 14px;
  line-height: 17px;
  height: 17px;
  margin-right: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  letter-spacing: 0;
  color: var(--text2);
}

.header-bar .trending-item .trending-mark {
  height: 14px;
  margin-right: 16px;
}

.header-bar .suggestions {
  margin-top: -6px;
  margin-bottom: -6px;
}

.header-bar .suggest-item {
  height: 32px;
  display: block;
  line-height: 32px;
  font-size: 14px;
  position: relative;
  text-align: left;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
  cursor: pointer;
  padding: 0 16px;
  margin-bottom: 4px;
}

.header-bar .suggest-item:hover,
.header-bar .suggest-item:focus {
  outline: none;
  background: var(--graph_bg_thick);
}

.right-entry {
  display: flex;
  align-items: center;
  margin-left: 20px;
  flex-shrink: 0; /*容器空间不足时不缩小，即固定大小*/
}

.default-login {
  position: absolute;
  top: 5px;
  left: 10px;
  z-index: 2;
  display: block;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background-color: var(--main-color);
  text-align: center;
  line-height: 38px;
  color: rgba(255, 255, 255, 0.9);
}

.header-avatar-wrap {
  position: relative;
  box-sizing: content-box;
  padding-right: 10px;
  width: 50px;
  height: 50px;
  cursor: pointer;
}

.header-avatar-wrap--container {
  position: relative;
  z-index: 2;
}

.mini-avatar--small {
  position: absolute;
  top: 5px;
  left: 10px;
  z-index: 2;
  display: block;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  box-sizing: border-box; /* 让内边距不增加宽度 */
  border: 2px solid #fff;
  transition: width 0.3s ease, height 0.3s ease, top 0.3s ease, left 0.3s ease; /* 添加平滑过渡效果 */
}

.v-img {
  position: relative;
  display: inline-block;
  line-height: 1;
  width: 100%;
  height: 100%;
  vertical-align: middle;
  border-radius: 50%;
  background-color: transparent;
}

/* 放大头像 */
.header-avatar-wrap:hover .mini-avatar--small {
  top: 15px;
  left: -35px;
  width: 90px;
  height: 90px;
}

.header-avatar-wrap:hover .mini-avatar--small.shrink {
  animation: shrink 0.3s both; /* 初始状态为缩小 */
}

@keyframes shrink {
  to {
    transform: scale(1); /* 缩小到原始大小 */
  }
}

.v-img img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: inherit;
  border-radius: 50%;
  image-rendering: -webkit-optimize-contrast;
}

.v-popover {
  position: absolute;
  z-index: 1;
  padding-top: 20px;
  margin-left: -20px;
  cursor: default;
}

.to-bottom {
  top: 100%;
  left: 50%;
  transform: translate3d(
    -50%,
    0,
    0
  ); /* 水平左移半个元素身位，使其水平与父元素居中 */
}

.avatar-panel-popover {
  width: 300px;
  background-color: #fff;
  border-radius: 8px;
  padding: 0 24px 18px;
  box-shadow: 0 0 30px rgba(0, 0, 0, 0.1);
  border: 1px solid var(--line_regular);
}

.popHide {
  animation: fade-out 0.2s ease-out forwards;
  transform-origin: top; /* 设置动画的旋转点为顶部 */
}

.popShow {
  animation: fade-in 0.2s ease-out forwards;
  transform-origin: top; /* 设置动画的旋转点为顶部 */
}

/* 淡入动画 */
@keyframes fade-in {
  0% {
    opacity: 0; /* 初始状态透明 */
    transform: translateY(-10px); /* 向上平移 10px，将元素隐藏在顶部 */
  }
  100% {
    opacity: 1; /* 最终状态不透明 */
    transform: translateY(0); /* 平移恢复到原始位置 */
  }
}

/* 淡出动画 */
@keyframes fade-out {
  0% {
    opacity: 1; /* 初始状态不透明 */
    transform: translateY(0); /* 原始位置 */
  }
  100% {
    opacity: 0; /* 最终状态透明 */
    transform: translateY(-10px); /* 向上平移 10px，将元素隐藏在顶部 */
  }
}

.nickname {
  display: flex;
  justify-content: center;
  margin-top: 50px;
  margin-bottom: 6px;
  font-size: 18px;
  color: var(--text1);
}

.vip-level-tag {
  display: flex;
  align-items: center;
  justify-content: center;
}

.vip-tag {
  display: inline-block;
  box-sizing: border-box;
  max-width: 58px;
  height: 16px;
  color: #fff;
  background: var(--brand_blue);
  border-radius: 2px;
  line-height: 16px;
  font-size: 10px;
  padding: 0 3px;
  margin-right: 10px;
  overflow: hidden;
  white-space: nowrap;
  font-weight: 400;
  cursor: pointer;
}

.gender {
  display: flex;
  align-items: center;
  justify-content: center;
}

.female {
  background-color: var(--Pi2);
  color: var(--Pi5);
}

.male {
  background-color: var(--Lb2);
  color: var(--Lb5_u);
}

.vip-level-tag .iconfont {
  font-size: 12px;
  line-height: 14px;
}

.level {
  margin-right: 10px;
}

.coins {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  margin: 6px 0;
}

.coins-text {
  margin-right: 5px;
  color: var(--text3);
}

.coins-num {
  color: var(--text1);
}

.counts {
  display: flex;
  justify-content: space-between;
  padding: 0 20px;
  margin: 6px 0 12px 0;
}

.counts-item {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-direction: column;
  width: fit-content;
}

.count-num {
  font-size: 18px;
  color: #222222;
  transition: 0.3s;
}

.count-text {
  font-size: 12px;
  color: var(--text3);
  margin-top: 2px;
  transition: 0.3s;
}

.counts-item:hover .count-num,
.counts-item:hover .count-text {
  color: var(--brand_blue);
}

.single-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px;
  height: 38px;
  border-radius: 8px;
  color: var(--text2);
  font-size: 14px;
  cursor: pointer;
  transition: background-color 0.3s;
  margin-bottom: 2px;
}

.single-item:hover {
  background-color: var(--graph_bg_regular);
}

.single-item-left {
  display: flex;
  align-items: center;
}

.single-item span {
  line-height: 14px;
  margin-left: 10px;
}

.placeholder {
  margin: 6px 0 12px 0;
  border-bottom: 1px solid #ddd;
}

.logout {
  display: flex;
  justify-content: initial !important;
}

.slide-down .right-entry--outside .iconfont {
  color: var(--text1);
}

.slide-down .right-entry--outside {
  color: var(--text2);
}

.right-entry--outside {
  display: flex;
  align-items: center;
  flex-direction: column;
  flex-shrink: 0;
  margin-right: 0;
  min-width: 50px;
  text-align: center;
  font-size: 13px;
  color: #fff;
  cursor: pointer;
}

.right-entry--outside .iconfont {
  font-size: 20px;
  display: inline-block;
  position: relative;
}

.right-entry-item--upload {
  margin-left: 15px;
}

.not-login {
  user-select: none;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  padding: 22px 20px;
  width: 340px;
}

.not-login-tips {
  margin-bottom: 24px;
  font-size: 14px;
  line-height: 20px;
  text-align: center;
  color: var(--text3);
}

.not-login-btn {
  border-radius: 8px;
  color: #fff;
  background-color: var(--brand_blue);
  height: 40px;
  width: 100%;
  text-align: center;
  line-height: 40px;
  cursor: pointer;
  transition: 0.3s;
}

.not-login-btn:hover {
  background-color: var(--Pi4);
}

.upload-buttom {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: 16px;
  width: 90px;
  height: 34px;
  border-radius: 8px;
  background: #fb7299;
  color: #fff;
  text-align: center;
  font-size: 14px;
  line-height: 34px;
  cursor: pointer;
  transition: background-color 0.3s;
}

.upload-buttom:hover {
  background-color: #f992af;
}

.icon-shangchuan {
  margin-right: 5px;
  line-height: 34px;
  margin-top: -2px;
}

@media (max-width: 1279.9px) {
  .right-entry--outside {
    margin: 0 5px;
    min-width: 25px;
  }

  .upload-buttom {
    width: 34px;
    height: 34px;
    margin-left: 0;
  }

  .icon-shangchuan {
    margin-right: 0;
  }

  .right-entry--outside span,
  .upload-buttom span {
    display: none;
  }
}

/* 跳动效果 */
@keyframes jump {
  0%,
  100% {
    transform: translateY(0); /* 起始和结束状态，图标回到原位 */
  }
  50% {
    transform: translateY(-5px); /* 中间状态，图标向上跳动 20px */
  }
}

.default-entry:hover span,
.right-entry--outside:hover .iconfont {
  animation: jump 0.3s; /* 应用跳动动画效果 */
}
.v-popover-wrap .v-popover-content {
  height: 540px;
  width: 370px;
}
.v-popover-wrap .v-popover-content .video-panel {
  padding: 12px 0;
  overflow-y: auto;
  height: 493px;
}
.v-popover-wrap .video-item {
  display: block;
  padding: 10px 10px 10px 20px;
}
.v-popover-wrap .video-item:hover {
  background-color: var(--graph_bg_thick);
}
.v-popover-wrap .content-bottom {
  position: absolute;
  bottom: 0px;
  display: flex;
  align-items: center;
  justify-content: space-around;
  width: 100%;
  border-top: 1px solid var(--bg3);
}
.v-popover-wrap .content-bottom a {
  height: 45px;
  color: var(--text1);
  text-align: center;
  font-size: 14px;
  line-height: 45px;
}
</style>
