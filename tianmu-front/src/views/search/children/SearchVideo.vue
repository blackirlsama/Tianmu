<template>
  <div class="search-video">
    <div class="search-page i_wrapper">
      <div
        class="container"
        :style="
          videoList.length === 0 && !loading
            ? 'grid-template-columns: unset;justify-content: center;'
            : ''
        "
      >
        <el-empty
          v-if="videoList.length === 0 && !loading"
          description="暂无视频"
        />

        <template v-else
          ><VideoCard
            v-for="item in videoList"
            :key="item.videoId"
            :item="item"
            :isLoading="loading"
            dataType="search"
          ></VideoCard
        ></template>
      </div>
      <!-- <div class="search-bottom flex_center">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="this.$store.state.matchingCount[0]"
          :page-size="30"
          :pager-count="7"
          :current-page="page"
          @current-change="pageChange"
        ></el-pagination>
      </div> -->
    </div>
  </div>
</template>

<script>
import VideoCard from "@/components/VideoCard/VideoCard.vue";
import { fetchSearchVideo } from "@/api/user";
export default {
  name: "SearchVideo",
  components: {
    VideoCard,
  },
  data() {
    return {
      page: 1, // 当前页码
      videoList: [], // 查询到的相关视频
      loading: true, // 正在查询中
    };
  },
  props: {
    // 从路由参数获取的关键词
    keyword: String,
  },
  methods: {
    // 查询相关视频
    async searchVideos() {
      try {
        this.videoList = [];
        this.loading = true;
        const res = await fetchSearchVideo(this.keyword);
        if (res.code !== 200) return;
        this.videoList = res.data;
        console.log("视频列表：", this.videoList);
        this.loading = false;
      } catch (error) {
        console.log("搜索失败", error);
      }
    },

    // 换页
    pageChange(page) {
      this.page = page;
      this.searchVideos();
    },
  },
  created() {
    if (this.keyword) {
      this.searchVideos();
    }
  },
  watch: {
    keyword(curr) {
      if (curr) {
        this.page = 1;
        this.searchVideos();
      }
    },
  },
};
</script>

<style scoped>
.search-page {
  padding-bottom: 30px !important;
  margin-top: 30px !important;
  position: relative;
}

.container {
  grid-gap: 20px;
  display: grid;
  position: relative;
  width: 100%;
}

@media (max-width: 1399.9px) {
  .container {
    grid-column: span 4;
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (min-width: 1400px) {
  .container {
    grid-column: span 5;
    grid-template-columns: repeat(5, 1fr);
  }
}

@media (min-width: 1700px) {
  .container {
    grid-column: span 6;
    grid-template-columns: repeat(6, 1fr);
  }
}

@media (min-width: 2200px) {
  .container {
    grid-column: span 7;
    grid-template-columns: repeat(7, 1fr);
  }
}

.search-bottom {
  margin: 50px 0 20px;
}
</style>