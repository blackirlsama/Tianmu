<template>
  <div class="user-home">
    <!-- 主内容区 -->
    <div class="main-content">
      <div class="video-content">
        <!-- 点赞视频 -->
        <div class="video-section">
          <div class="section-header">
            <h2 class="section-title">
              <div class="title-text">{{ $route.params.userId === user.userId ? "我" : "他" }}点赞的视频</div>
              <div class="section-desc">
                <span>·</span>
                {{ likeVideoList.length }}
              </div>
            </h2>
          </div>

          <div
            class="video-list"
            :style="
              likeVideoList.length === 0
                ? 'grid-template-columns: unset;justify-content: center;'
                : ''
            "
          >
            <VideoCard
              v-for="item in likeVideoList"
              :key="item.videoId"
              :item="item"
              :isLoading="likeVideoLoading"
            ></VideoCard>

            <el-empty
              v-if="likeVideoList.length === 0 && !likeVideoLoading"
              description="暂无视频"
            />
          </div>
        </div>

        <!-- 投币视频 -->
        <div class="video-section">
          <div class="section-header">
            <h2 class="section-title">
              <div class="title-text">{{ $route.params.userId === user.userId ? "我" : "他" }}投币的视频</div>
              <div class="section-desc">
                <span>·</span>
                {{ coinVideoList.length }}
              </div>
            </h2>
          </div>

          <div
            class="video-list"
            :style="
              coinVideoList.length === 0
                ? 'grid-template-columns: unset;justify-content: center;'
                : ''
            "
          >
            <VideoCard
              v-for="item in coinVideoList"
              :key="item.videoId"
              :item="item"
              :isLoading="coinVideoLoading"
            ></VideoCard>

            <el-empty
              v-if="coinVideoList.length === 0 && !coinVideoLoading"
              description="暂无视频"
            />
          </div>
        </div>
      </div>

      <!-- 右侧边栏 -->
      <div class="right-sidebar">
        <div class="charge-section">
          <div class="user-info">
            <div class="header-section">个人资料</div>
            <div class="user-id">
              <i>🆔</i>
              <span>{{ $route.params.userId }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import VideoCard from "@/components/VideoCard/VideoCard.vue";
import { ElMessage } from "element-plus";
import { fetchLikeVideoList, fetchCoinVideoList } from "@/api/video";
import { useUserStore } from "@/store/user";
import { mapState } from "pinia";
export default {
  name: "UserHome",
  components: {
    VideoCard,
  },
  data() {
    return {
      likeVideoList: [],
      likeVideoLoading: true,
      coinVideoList: [],
      coinVideoLoading: true,
    };
  },
  computed: {
    ...mapState(useUserStore, ["user"]),
  },
  methods: {
    async getLikeVideoList() {
      try {
        const res = await fetchLikeVideoList(this.$route.params.userId);
        if (res.code === 200) {
          this.likeVideoList = res.data;
          this.likeVideoLoading = false;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
    async getCoinVideoList() {
      try {
        const res = await fetchCoinVideoList(this.$route.params.userId);
        if (res.code === 200) {
          this.coinVideoList = res.data;
          this.coinVideoLoading = false;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
  },

  created() {
    this.getLikeVideoList();
    this.getCoinVideoList();
  },
};
</script>

<style scoped>
/* 主内容区 */
.main-content {
  margin-top: 30px;
  display: flex;
  padding: 0 60px;
}

/* 视频管理区 */
.video-content {
  flex: 1;
}
.video-section {
  padding-bottom: 24px;
  border-bottom: 1px solid var(--line_regular);
  margin-bottom: 24px;
}

.section-header {
  display: flex;
  align-items: center;
  margin-bottom: 15px;
}

.section-title {
  display: flex;
  align-items: center;
}
.section-title .title-text {
  font-weight: bold;

  font-size: 24px;
  font-weight: 600;
  color: var(--v_text1);
}

.section-title .section-desc {
  font-size: 16px;
  margin-left: 6px;
  color: var(--v_text2);
}

.video-list {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 20px;
}

/* 操作按钮 */
.btn {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  margin-left: 10px;
  transition: all 0.3s;
}

.btn-outline {
  background-color: transparent;
  border: 0.8px solid #c9ccd0;
}
.action-buttons {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

/* 右侧边栏 */
.right-sidebar {
  width: 240px;
  margin-left: 20px;
}

.charge-section {
  background-color: #eee;
  border-radius: 8px;
  padding: 15px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
}
.header-section {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--v_text1);
  font-size: 16px;
  font-weight: 500;
}

.user-id {
  font-size: 14px;
  color: #666;
  display: flex;
  align-items: center;
}

.user-id i {
  margin-right: 5px;
}
</style>