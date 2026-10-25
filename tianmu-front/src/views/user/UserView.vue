<template>
  <div class="space">
    <HeaderBar :isFixHeaderBar="true"></HeaderBar>
    <div class="container">
      <!-- 头部横幅 -->
      <div class="banner">
        <div class="banner-content">
          <div class="banner-left">
            <VAvatar :img="userInfo.avatar" :size="64"></VAvatar>

            <div class="user-info">
              <h1>{{ userInfo.nickname }}</h1>
              <p>
                {{
                  userInfo.description
                    ? userInfo.description
                    : "这个人很懒，什么也没写"
                }}
              </p>
            </div>
          </div>
          <div class="banner-right" v-if="user.userId !== $route.params.userId">
            <button
              class="btn btn-primary action-btn"
              :class="{ gray: userInfo.follow === 1 || userInfo.follow === 2 }"
              @click="handleFocus"
            >
              {{ focusText }}
            </button>
          </div>
        </div>
      </div>

      <!-- 导航栏 -->
      <div class="nav">
        <ul class="nav-links">
          <li
            class="nav-item"
            :class="{ active: activeTab === $route.params.userId }"
            @click="clickNavItem($route.params.userId)"
          >
            <i class="iconfont icon-zhuye"></i> 主页
            <span
              v-show="activeTab === $route.params.userId"
              class="nav-tab-cursor"
            ></span>
          </li>
          <li
            class="nav-item"
            :class="{ active: activeTab === 'upload' }"
            @click="clickNavItem('upload', '/upload')"
          >
            <i class="iconfont icon-tougao"></i> 投稿<span
              style="font-size: 12px; margin-left: 3px"
            ></span>
            <span v-show="activeTab === 'upload'" class="nav-tab-cursor"></span>
          </li>
          <li
            class="nav-item"
            :class="{ active: activeTab === 'favlist' }"
            @click="clickNavItem('favlist', '/favlist')"
          >
            <i class="iconfont icon-user-sky-shoucang"></i> 收藏<span
              style="font-size: 12px; margin-left: 3px"
            ></span>
            <span
              v-show="activeTab === 'favlist'"
              class="nav-tab-cursor"
            ></span>
          </li>
        </ul>
        <!-- <div class="search-box">
          <input
            type="text"
            class="search-input"
            placeholder="搜索视频、动态"
          />
        </div> -->
        <div class="user-stats">
          <div
            class="stat-item"
            :class="{ active: activeTab === 'follow' }"
            @click="clickNavItem('follow', '/relation/follow')"
          >
            <span class="stat-label">关注数</span>
            <span class="stat-value">{{ handleNum(userInfo.following) }}</span>
          </div>
          <div
            class="stat-item"
            :class="{ active: activeTab === 'fans' }"
            @click="clickNavItem('fans', '/relation/fans')"
          >
            <span class="stat-label">粉丝数</span>
            <span class="stat-value">{{ handleNum(userInfo.followers) }}</span>
          </div>
          <!-- <div class="stat-item">
            <span class="stat-label">获赞数</span>
            <span class="stat-value">14.3万</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">播放数</span>
            <span class="stat-value">933.3万</span>
          </div> -->
        </div>
      </div>
      <router-view></router-view>
    </div>
  </div>
</template>
<script>
import HeaderBar from "@/components/headerBar/HeaderBar.vue";
import VAvatar from "@/components/avatar/VAvatar.vue";
import { useUserStore } from "@/store/user";
import { mapState, mapActions } from "pinia";
import { fetchUserInfo } from "@/api/user";
import { ElMessage } from "element-plus";
import { handleNum } from "@/lib/utils.js";
export default {
  name: "UserView",
  components: {
    HeaderBar,
    VAvatar,
  },
  data() {
    return {
      activeTab: "",
      userInfo: {},
    };
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin"]),
    focusText() {
      if (this.userInfo.follow === 2) {
        return "已互粉";
      }
      if (this.userInfo.follow === 1) {
        return "已关注";
      }
      return "+ 关注";
    },
  },
  created() {
    this.getUserInfo();
    // 初始化activeTab，以路由的路径来判断当前激活的是哪个导航栏
    let pathArray = this.$route.path.split("/");
    this.activeTab = pathArray[pathArray.length - 1];
  },
  methods: {
    ...mapActions(useUserStore, ["handleFocusAction"]),
    // 点击导航栏跳转页面
    clickNavItem(tab, path = "") {
      this.activeTab = tab;
      const absPath = `/user/${this.$route.params.userId}${path}`;
      if (this.$route.path === absPath) return;
      this.$router.push(absPath);
    },
    // 获取用户信息
    async getUserInfo() {
      try {
        const res = await fetchUserInfo(
          this.user.userId,
          this.$route.params.userId
        );
        if (res.code === 200) {
          this.userInfo = res.data;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
    // 转换大于1w的数字
    handleNum(num) {
      return handleNum(num);
    },
    // 处理关注和取消关注
    async handleFocus() {
      if (!this.isLogin) {
        return ElMessage.warning("请先登录");
      }
      const res = await this.handleFocusAction(
        this.user.userId,
        this.$route.params.userId,
        this.userInfo.follow
      );
      if (res && res.code === 200) {
        this.getUserInfo();
      }
    },
  },
  watch: {
    $route(newVal) {
      const pathArray = newVal.path.split("/");
      const tab = pathArray[pathArray.length - 1];
      this.activeTab = tab;
    },
  },
};
</script>
<style scoped>
.container {
  /* max-width: 1200px; */
  margin: 0 auto;
  padding: 64px 0px 0px;
}

/* 头部横幅 */
.banner {
  width: 100%;
  height: 200px;
  background-image: url("@/assets/img/bilibili/user_bg.avif");
  background-repeat: no-repeat;
  background-size: cover;
  background-position: center top;
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: self-end;
}
.banner::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: rgba(0, 0, 0, 0.15);
  z-index: 1;
}
.banner-content {
  position: relative;
  z-index: 2;
  color: white;
  padding: 0 60px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.banner-left {
  display: flex;
  align-items: center;
}

.user-info {
  margin-left: 20px;
}
.user-info h1 {
  font-size: 20px;
  margin-bottom: 5px;
}

.user-info p {
  font-size: 14px;
  opacity: 0.8;
}

.banner-right {
  display: flex;
}

.btn {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.3s;
}

.btn-primary {
  background-color: #00a1d6;
  color: white;
  border: none;
}
.action-btn {
  width: 150px;
}

.action-btn.gray {
  border: 1px solid rgba(255, 255, 255, 0.2);
  background-color: rgba(255, 255, 255, 0.14);
  transition: all 0.3s;
}
/* 导航栏 */
.nav {
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
  padding: 0 60px;
  position: relative;
  z-index: 10;
  display: flex;
  justify-content: space-between;
  align-items: center;
  position: sticky;
  top: 63px;
}

.nav-links {
  display: flex;
  list-style: none;
}

.nav-item {
  margin-right: 25px;
  display: flex;
  align-items: center;
  color: #666;
  font-size: 14px;
  cursor: pointer;
  position: relative;
  padding: 15px 0;
}
.nav-item:hover {
  color: var(--main-color);
}

.nav-item.active,
.stat-item.active .stat-label,
.stat-item.active .stat-value {
  color: var(--main-color);
}

.nav-item i {
  margin-right: 5px;
}
.nav-tab-cursor {
  position: absolute;
  bottom: -9px;
  width: 90%;
  height: 3px;
  background-color: var(--main-color);
  left: 50%;
  transform: translateX(-50%);
}

.search-box {
  flex: 1;
  margin: 0 20px;
  position: relative;
  max-width: 300px;
}

.search-input {
  width: 100%;
  padding: 8px 15px;
  border-radius: 20px;
  border: 1px solid #eee;
  background-color: #f5f5f5;
  outline: none;
}

.user-stats {
  display: flex;
  text-align: center;
  font-size: 13px;
  color: #666;
  padding: 15px 0;
}

.stat-item {
  margin-left: 20px;
  cursor: pointer;
}
.stat-item:hover .stat-label,
.stat-item:hover .stat-value {
  color: var(--main-color);
}
.stat-value {
  font-size: 14px;
  color: #18191c;
  display: block;
}

.stat-label {
  font-size: 12px;
  color: #999;
}
</style>