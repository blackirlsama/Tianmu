<template>
  <div class="user-home">
    <!-- 主内容区 -->
    <div class="main-content">
      <div class="video-content">
        <!-- 投稿视频 -->
        <div class="video-section">
          <div class="section-header">
            <h2 class="section-title">
              <div class="title-text">
                {{ $route.params.userId === user.userId ? "我" : "他" }}收藏的视频
              </div>
              <div class="section-desc">
                <span>·</span>
                {{ coltVideoList.length }}
              </div>
            </h2>
          </div>
          <div
            class="video-list"
            :style="
              coltVideoList.length === 0
                ? 'grid-template-columns: unset;justify-content: center;'
                : ''
            "
          >
            <VideoCard
              v-for="item in coltVideoList"
              :key="item.videoId"
              :item="item"
              :isLoading="coltVideoLoading"
            ></VideoCard>

            <el-empty
              v-if="coltVideoList.length === 0 && !coltVideoLoading"
              description="暂无视频"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import VideoCard from "@/components/VideoCard/VideoCard.vue";
import { ElMessage } from "element-plus";
import { fetchColtVideoList } from "@/api/video";
import { useUserStore } from "@/store/user";
import { mapState } from "pinia";
export default {
  name: "UserHome",
  components: {
    VideoCard,
  },
  data() {
    return {
      coltVideoList: [],
      coltVideoLoading: true,
    };
  },
  computed: {
    ...mapState(useUserStore, ["user"]),
  },
  methods: {
    async getColtVideoList() {
      try {
        const res = await fetchColtVideoList(this.$route.params.userId);
        if (res.code === 200) {
          this.coltVideoList = res.data;
          this.coltVideoLoading = false;
        }
      } catch (error) {
        ElMessage.error(error);
      }
    },
  },

  created() {
    this.getColtVideoList();
  },
};
</script>

<style scoped lang="scss">
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
  margin-bottom: 24px;
  .section-header {
    display: flex;
    align-items: center;
    margin-bottom: 15px;
    .section-title {
      display: flex;
      align-items: center;
      .title-text {
        font-weight: bold;
        font-size: 24px;
        font-weight: 600;
        color: var(--v_text1);
      }
      .section-desc {
        font-size: 16px;
        margin-left: 6px;
        color: var(--v_text2);
      }
    }
  }
  .video-list {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 20px;
  }
}
</style>