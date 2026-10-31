import { defineStore } from "pinia";
import { fetchFocus, fetchUnfocus } from "@/api/user";
import { ElMessage } from "element-plus";
import { TIANMU_USER, TIANMU_LOGIN } from "@/lib/config"
import { hasAuthToken, readStoredUser } from "@/lib/auth"
export const useUserStore = defineStore("user", {
  state: () => ({
    // 当前用户
    user: readStoredUser(),
    // 控制登录注册对话框的显示与隐藏
    dialogVisible: false,
  }),
  getters: {
    // 登录态只由网站自己的认证 token 决定，避免布尔标记与凭证脱节。
    isLogin: (state) => hasAuthToken(state.user),
  },
  actions: {
    login(user) {
      // 登录后，持久化用户信息
      this.user = user;
      localStorage.setItem(TIANMU_USER, JSON.stringify(user));
      console.log("login", this.user);
    },
    logout() {
      // 退出登录，清除用户信息
      this.user = {};
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
    syncCoinCount(count) {
      if (this.user.coinCount === count) return;
      this.user.coinCount = count;
      localStorage.setItem(TIANMU_USER, JSON.stringify(this.user));
    },
    updateVideoCount() {
      this.user.videoCount++
      localStorage.setItem(TIANMU_USER, JSON.stringify(this.user))
    }
  },
});
