import { defineStore } from "pinia";
import { fetchFocus, fetchUnfocus } from "@/api/user";
import { ElMessage } from "element-plus";
import { TIANMU_USER, TIANMU_LOGIN } from "@/lib/config"
export const useUserStore = defineStore("user", {
  state: () => ({
    // 当前用户
    user: JSON.parse(localStorage.getItem(TIANMU_USER)) || {},
    // 是否登录
    isLogin: JSON.parse(localStorage.getItem(TIANMU_LOGIN)) || false,
    // 控制登录注册对话框的显示与隐藏
    dialogVisible: false,
  }),
  actions: {
    login(user) {
      // 登录后，持久化用户信息
      this.user = user;
      this.isLogin = true;
      localStorage.setItem(TIANMU_USER, JSON.stringify(user));
      localStorage.setItem(TIANMU_LOGIN, JSON.stringify(true));
      console.log("login", this.user);
    },
    logout() {
      // 退出登录，清除用户信息
      this.user = {};
      this.isLogin = false;
      localStorage.removeItem(TIANMU_USER);
      localStorage.removeItem(TIANMU_LOGIN);
    },
    async handleFocusAction(userId, creatorId, focusType) {
      try {
        if (focusType === 0) {
          const res = await fetchFocus(userId, creatorId);
          if (res.code === 200) {
            this.user.follow += 1;
            ElMessage.success("关注成功");
            return res
          }
        } else {
          const res = await fetchUnfocus(userId, creatorId);
          if (res.code === 200) {
            this.user.follow -= 1;
            ElMessage.success("已取消关注");
            return res
          }
        }
      } catch (error) {
        console.log(error);
      }
    },
    updateDialogVisible(val) {
      this.dialogVisible = val;
    },
    updateUserCoin() {
      this.user.coinCount--
      localStorage.setItem(TIANMU_USER, JSON.stringify(this.user));

    },
    updateVideoCount() {
      this.user.videoCount++
      localStorage.setItem(TIANMU_USER, JSON.stringify(this.user))
    }
  },
});
