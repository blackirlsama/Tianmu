<template>
  <div class="platform-view">
    <!-- 头部 -->
    <div class="platform-header">
      <div class="header-content">
        <div class="left-block">
          <!-- <div class="logo-container">
            <img src="@/assets/img/icon_hot.png" alt="" />
            <span>创作中心</span>
          </div> -->
          <div class="entry" @click="$router.push('/')">
            <i class="iconfont icon-tv"></i>
            <span>主站</span>
          </div>
        </div>
        <div class="right-block">
          <!-- 未登录状态 -->
          <div class="not-login" v-if="!isLogin">
            <div class="not-login-text">未登录</div>
          </div>
          <VPopover pop-style="padding-top: 10px;" v-else>
            <template #reference>
              <div class="avatar">
                <img :src="user.avatar" alt="" />
              </div>
            </template>
            <template #content>
              <div style="width: 144px">
                <div class="logout" @click="handleLogout">
                  <i class="iconfont icon-logout"></i>
                  <span>退出登录</span>
                </div>
              </div>
            </template>
          </VPopover>

          <!-- <div class="tips">
            成为UP主的第1145天
            <i class="iconfont icon-youjiantou"></i>
          </div> -->

          <!-- 线条 -->
          <!-- <div class="line-divid"></div> -->

          <!-- 消息 -->
          <!-- <VPopover pop-style="padding-top: 10px;">
            <template #reference>
              <div class="message">
                <i class="iconfont icon-xinfeng"></i>
                <div class="red-num--dynamic" v-if="true">0</div>
              </div>
            </template>
            <template #content>
              <div class="message-entry-popover" v-if="true">
                <div class="message-inner-list">
                  <div
                    class="message-inner-list__item"
                  >
                    回复我的
                    <span class="notify-number" v-if="true"> 0 </span>
                  </div>
                  <div
                    class="message-inner-list__item"
                  >
                    @ 我的
                    <span class="notify-number" v-if="true"> 0 </span>
                  </div>
                  <div
                    class="message-inner-list__item"                  >
                    收到的赞
                    <span class="notify-number" v-if="true"> 0 </span>
                  </div>
                  <div
                    class="message-inner-list__item"
                  >
                    系统消息
                    <span class="notify-number" v-if="true"> 0 </span>
                  </div>
                  <div
                    class="message-inner-list__item"
                  >
                    我的消息
                    <span class="notify-number" v-if="true"> 0 </span>
                  </div>
                </div>
              </div>
            </template>
          </VPopover> -->
        </div>
      </div>
    </div>
    <!-- 左侧导航 -->
    <!-- <div class="platform-nav">
      <div
        class="nav-upload-btn"
        @click="this.$router.push('/platform/upload')"
      >
        <i class="iconfont icon-shangchuan"></i>
        投稿
      </div>
      <el-menu :default-active="active" class="nav-menu" :router="true">
        <el-menu-item index="/platform/home">
          <i class="iconfont icon-zhuye"></i>
          <span>首页</span>
        </el-menu-item>
        <el-sub-menu index="/platform/upload-manager">
          <template #title>
            <i class="iconfont icon-gaojian"></i>
            <span>内容管理</span>
          </template>
          <el-menu-item index="/platform/upload-manager/manuscript"
            >稿件管理</el-menu-item
          >
          <el-menu-item index="/platform/upload-manager/appeal"
            >申诉管理</el-menu-item
          >
        </el-sub-menu>
        <el-menu-item index="/platform/data-up">
          <i class="iconfont icon-shuju"></i>
          <span>数据中心</span>
        </el-menu-item>
        <el-sub-menu index="/platform/interaction">
          <template #title>
            <i class="iconfont icon-hudong"></i>
            <span>互动管理</span>
          </template>
          <el-menu-item index="/platform/comment">评论管理</el-menu-item>
          <el-menu-item index="/platform/danmu">弹幕管理</el-menu-item>
        </el-sub-menu>
      </el-menu>
    </div> -->
    <!-- 主体 -->
    <div class="platform-main" v-if="true">
      <div class="content-body">
        <router-view></router-view>
      </div>
    </div>
  </div>
</template>

<script>
import VPopover from "@/components/popover/VPopover.vue";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/store/user";
import { mapState, mapActions } from "pinia";
import { fetchLogout } from "@/api/user.js";

export default {
  name: "PlatformView",
  components: {
    VPopover,
  },
  data() {
    return {
      active: "",
      path: [
        "/platform/home",
        "/platform/upload-manager/manuscript",
        "/platform/upload-manager/appeal",
        "/platform/data-up",
        "/platform/comment",
        "/platform/danmu",
      ],
    };
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin"]),
  },
  created() {
    for (let i = 0; i < this.path.length; i++) {
      if (this.$route.path.startsWith(this.path[i])) {
        this.active = this.path[i].slice();
        break;
      }
    }
  },
  methods: {
    ...mapActions(useUserStore, ["logout"]),

    // 退出登录
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
  },
};
</script>

<style scoped>
.platform-header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 60px;
  z-index: 900;
  min-width: 960px;
}

.header-content {
  height: 60px;
  display: -webkit-box;
  display: -webkit-flex;
  display: flex;
  -webkit-box-pack: justify;
  -webkit-justify-content: space-between;
  justify-content: space-between;
  -webkit-box-align: center;
  -webkit-align-items: center;
  align-items: center;
  padding: 0 100px 0 32px;
  box-sizing: border-box;
  background: #fff;
  box-shadow: 0 2px 10px 0 rgba(0, 0, 0, 0.05);
  margin-right: calc(-100vw + 100%);
  min-width: 800px;
  width: 100%;
}

.left-block {
  display: -webkit-box;
  display: -webkit-flex;
  display: flex;
  -webkit-box-align: center;
  -webkit-align-items: center;
  align-items: center;
  height: 100%;
}

.logo-container {
  height: 40%;
  display: flex;
  align-items: center;
  margin-right: 10px;
  cursor: pointer;
}

.logo-container img {
  height: 120%;
  width: 88.76px;
}

.logo-container span {
  font-size: 21px;
  font-weight: 600;
  color: var(--brand_blue);
}

.entry {
  margin-left: 20px;
  color: var(--text2);
  font-size: 14px;
  cursor: pointer;
}

.entry .iconfont {
  margin-right: 5px;
  font-weight: 600;
}

.right-block {
  display: -webkit-box;
  display: -webkit-flex;
  display: flex;
  -webkit-box-align: center;
  -webkit-align-items: center;
  align-items: center;
  font-size: 16px;
  color: #757575;
  height: 100%;
}

.avatar {
  position: relative;
  line-height: 1;
  height: 30px;
  width: 30px;
  border-radius: 50%;
  cursor: pointer;
}

.avatar img {
  height: 100%;
  width: 100%;
  border-radius: 50%;
}

.logout {
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  padding: 10px 14px;
  color: var(--text2);
  font-size: 14px;
  cursor: pointer;
  transition: background-color 0.3s;
}

.logout:hover {
  background-color: var(--graph_bg_regular);
}

.logout span {
  margin-left: 10px;
  line-height: 20px;
}

.tips {
  display: flex;
  align-items: center;
  background: rgba(250, 142, 87, 0.1);
  border: 1px solid rgba(250, 142, 87, 0.43);
  border-radius: 15px;
  padding: 5px 12px 5px 16px;
  font-size: 12px;
  color: #fa8e57;
  text-align: center;
  margin-right: 32px;
  margin-left: 12px;
  cursor: pointer;
}

.line-divid {
  width: 1px;
  height: 16px;
  background: #e7e7e7;
  margin-right: 32px;
}

.message {
  cursor: pointer;
}

.message .iconfont {
  font-size: 22px;
  font-weight: 600;
  color: var(--text2);
}

.red-num--dynamic {
  cursor: auto;
  user-select: none;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  z-index: 1;
  position: absolute;
  top: -5px;
  left: 12px;
  padding: 0 4px;
  min-width: 15px;
  border-radius: 10px;
  background-color: var(--stress_red);
  color: #fff;
  font-size: 12px;
  line-height: 15px;
}

.message-entry-popover {
  overflow: hidden;
  width: 142px;
}

.message-entry-popover .message-inner-list {
  display: flex;
  flex-direction: column;
  padding: 12px 0;
}

.message-entry-popover .message-inner-list__item {
  position: relative;
  display: flex;
  align-items: center;
  padding: 10px 0 10px 27px;
  color: var(--text2);
  text-align: left;
  font-size: 14px;
  transition: background-color 0.3s;
  cursor: pointer;
}

.message-entry-popover .message-inner-list__item:hover {
  background-color: var(--graph_bg_thick);
}

.notify-number {
  position: absolute;
  right: 17px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--stress_red);
  color: #fff;
  font-size: 12px;
  line-height: 16px;
}

.platform-nav {
  position: fixed;
  -webkit-transform: translateZ(0);
  transform: translateZ(0);
  padding-top: 60px;
  top: 0;
  left: 0;
  border-right: 1px solid #f4f4f4;
  background: #fff;
  z-index: 10;
  height: 100%;
  width: 200px;
  overflow-x: hidden;
  overflow-y: auto;
  font-family: PingFangSC-Regular, Microsoft YaHei, Arial, Helvetica, sans-serif;
}

.platform-nav::-webkit-scrollbar {
  width: 4px;
}

.platform-nav::-webkit-scrollbar-thumb {
  border-radius: 4px;
  background-color: #ddd;
}

.platform-nav .iconfont {
  font-size: 20px;
  margin-right: 20px;
}

.nav-upload-btn {
  width: 136px;
  height: 40px;
  border-radius: 2px;
  background-color: var(--brand_blue);
  margin: 24px auto 17px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
  cursor: pointer;
}

.nav-upload-btn:hover {
  background-color: #fa799e;
}

.nav-upload-btn .iconfont {
  font-size: 18px;
  margin-right: 10px;
}

.platform-main {
  position: relative;
  /* padding: 60px 0 50px 200px; */
  padding: 60px 0 50px 0;
  background: #fafafa;
  /* min-width: 1124px; */
  display: flow-root;
  min-height: 100%;
}

.content-body {
  width: inherit;
  min-height: calc(100vh - 126px);
  background: #fff;
  margin: 16px 72px 0;
  padding-bottom: 20px;
  display: flow-root;
}

/* element 导航元素 */
.el-menu {
  border-right: unset !important;
}

.platform-nav {
  --el-menu-active-color: var(--brand_blue);
  --el-menu-level-padding: 40px;
}

.platform-nav /deep/ .el-sub-menu__title:hover,
.el-menu-item:hover {
  background-color: var(--graph_bg_thin) !important;
}
</style>
